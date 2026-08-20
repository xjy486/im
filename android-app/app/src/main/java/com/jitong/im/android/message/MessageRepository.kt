package com.jitong.im.android.message

import androidx.room.withTransaction
import com.jitong.im.android.local.AccountDatabase
import com.jitong.im.android.local.LocalConversationReadStateEntity
import com.jitong.im.android.local.LocalMessageEntity
import com.jitong.im.android.local.PendingMessageCommandEntity
import com.jitong.im.android.local.SyncStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class MessageRepository(
    private val api: MessageApi,
    private val syncApi: SyncApi,
    private val database: () -> AccountDatabase?,
    private val webSocket: MessageWebSocket,
    private val deviceId: () -> UUID? = { null },
) {
    private val pendingAcks = ConcurrentHashMap<UUID, CompletableDeferred<MessageResponse>>()
    private val pendingFlushMutex = Mutex()
    private var pendingSendScheduler: (() -> Unit)? = null
    private var automaticSendingEnabled = false

    fun observe(conversationId: UUID): Flow<List<LocalMessageEntity>> =
        database()?.messageDao()?.observe(conversationId.toString())
            ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun loadHistory(conversationId: UUID) {
        val db = database() ?: return
        // The authoritative sequence is supplied by the server. The local SQL
        // query is intentionally not used as an ordering source for transport.
        val response = api.history(conversationId).messageBodyOrThrow()
        withContext(Dispatchers.IO) {
            val dao = db.messageDao()
            response.messages.forEach {
                db.pendingCommandDao().delete(it.clientMsgId.toString())
                dao.deleteByClientMsgId(it.clientMsgId.toString())
                dao.upsert(it.toEntity())
            }
        }
    }

    suspend fun openConversation(conversationId: UUID) {
        val db = database() ?: return
        val currentUserId = withContext(Dispatchers.IO) {
            db.accountDao().current()?.userId?.let(UUID::fromString)
        } ?: return
        restoreConversation(conversationId, currentUserId, db)
    }

    suspend fun markRead(conversationId: UUID, readSeq: Long) {
        require(readSeq >= 0) { "Read sequence must not be negative" }
        val db = database() ?: return
        val currentUserId = withContext(Dispatchers.IO) {
            db.accountDao().current()?.userId?.let(UUID::fromString)
        } ?: return
        val current = withContext(Dispatchers.IO) {
            db.conversationReadStateDao().find(
                conversationId.toString(),
                currentUserId.toString(),
            )?.readSeq ?: 0L
        }
        val requested = ReadSeqPolicy.advance(current, readSeq)
        if (requested == current) {
            return
        }
        val readStates = syncApi.markRead(
            conversationId,
            ReadStateRequest(requested),
        ).readBodyOrThrow()
        applyReadStates(readStates)
    }

    suspend fun synchronize(currentUserId: UUID, requestedUntil: Long) {
        val db = database() ?: return
        var afterSeq = withContext(Dispatchers.IO) {
            db.syncStateDao().current()?.lastSyncSeq ?: 0L
        }
        if (requestedUntil < afterSeq) {
            throw IOException("Server high watermark moved behind local cursor")
        }
        while (afterSeq < requestedUntil) {
            val page = try {
                syncApi.page(afterSeq, requestedUntil).syncBodyOrThrow()
            } catch (exception: SyncResetRequiredException) {
                fullRestore(currentUserId, requestedUntil)
                return
            }
            try {
                SyncCursorPolicy.validatePage(page, afterSeq)
            } catch (exception: SyncResetRequiredException) {
                fullRestore(currentUserId, requestedUntil)
                return
            }
            page.events
                .filter { it.eventType == "MESSAGE_CREATED" && it.conversationId != null }
                .mapNotNull { it.conversationId }
                .distinct()
                .forEach { conversationId ->
                    restoreConversation(conversationId, currentUserId, db)
                }
            page.events
                .filter { it.eventType == "CONVERSATION_READ" && it.conversationId != null }
                .mapNotNull { it.conversationId }
                .distinct()
                .map { conversationId ->
                    syncApi.readStates(conversationId).readBodyOrThrow()
                }
                .toList()
                .also { readStatePages ->
                    withContext(Dispatchers.IO) {
                        db.withTransaction {
                            readStatePages.forEach { readStatePage ->
                                applyReadStatesInTransaction(db, readStatePage)
                            }
                        }
                    }
                }
            val nextAfter = page.nextAfterSeq
            if (nextAfter <= afterSeq && page.hasMore) {
                throw IOException("Sync cursor did not advance")
            }
            afterSeq = nextAfter
            if (!page.hasMore && afterSeq < requestedUntil) {
                throw IOException("Sync ended before the requested high watermark")
            }
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    db.syncStateDao().upsert(
                        SyncStateEntity(
                            deviceId = deviceId()?.toString().orEmpty(),
                            lastSyncSeq = afterSeq,
                            lastFullRestoreAt = db.syncStateDao().current()?.lastFullRestoreAt,
                        ),
                    )
                }
            }
        }
        syncApi.acknowledge(SyncAckRequest(requestedUntil)).syncBodyOrThrow()
    }

    private suspend fun fullRestore(currentUserId: UUID, highWatermark: Long) {
        val db = database() ?: return
        val conversations = syncApi.conversations().syncBodyOrThrow()
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.messageDao().clearAccepted()
                db.conversationReadStateDao().clearAll()
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        deviceId = deviceId()?.toString().orEmpty(),
                        lastSyncSeq = 0,
                        lastFullRestoreAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        conversations
            .forEach { conversation ->
                restoreConversation(conversation.conversationId, currentUserId, db)
                applyReadStates(
                    syncApi.readStates(conversation.conversationId).readBodyOrThrow(),
                )
            }
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        deviceId = deviceId()?.toString().orEmpty(),
                        lastSyncSeq = highWatermark,
                        lastFullRestoreAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        syncApi.acknowledge(SyncAckRequest(highWatermark)).syncBodyOrThrow()
    }

    private suspend fun restoreConversation(
        conversationId: UUID,
        currentUserId: UUID,
        db: AccountDatabase,
    ): Long {
        var afterConversationSeq = 0L
        while (true) {
            val history = api.history(conversationId, afterConversationSeq, 200).messageBodyOrThrow()
            if (history.messages.isEmpty()) {
                return afterConversationSeq
            }
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    val dao = db.messageDao()
                    history.messages.forEach {
                        db.pendingCommandDao().delete(it.clientMsgId.toString())
                        dao.deleteByClientMsgId(it.clientMsgId.toString())
                        dao.upsert(it.toEntity(
                            localState = if (it.senderId == currentUserId) "SENT" else "RECEIVED",
                        ))
                    }
                }
            }
            val nextSequence = history.messages.last().conversationSeq
            if (nextSequence <= afterConversationSeq || history.messages.size < 200) {
                return nextSequence
            }
            afterConversationSeq = nextSequence
        }
    }

    suspend fun send(conversationId: UUID, text: String): UUID {
        require(text.isNotBlank()) { "Message text must not be blank" }
        val db = database() ?: throw IOException("No local account database is open")
        val clientMsgId = UUID.randomUUID()
        val createdAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.pendingCommandDao().upsert(
                    PendingMessageCommandEntity(
                        clientMsgId = clientMsgId.toString(),
                        conversationId = conversationId.toString(),
                        text = text,
                        createdAt = createdAt,
                        status = SendCommandState.PENDING,
                    ),
                )
                db.messageDao().upsert(
                    LocalMessageEntity(
                        messageId = "local:$clientMsgId",
                        conversationId = conversationId.toString(),
                        senderId = "local",
                        clientMsgId = clientMsgId.toString(),
                        conversationSeq = null,
                        type = "TEXT",
                        state = "ACTIVE",
                        localState = "QUEUED",
                        text = text,
                        serverAcceptedAt = null,
                        createdAt = createdAt,
                    ),
                )
            }
        }
        pendingSendScheduler?.invoke()
        if (automaticSendingEnabled && webSocket.isConnected()) {
            flushOnlinePending()
        }
        return clientMsgId
    }

    fun setPendingSendScheduler(scheduler: (() -> Unit)?) {
        pendingSendScheduler = scheduler
    }

    fun enableAutomaticSending() {
        automaticSendingEnabled = true
    }

    fun disableAutomaticSending() {
        automaticSendingEnabled = false
    }

    suspend fun prepareForLogout() {
        pendingFlushMutex.withLock {
            automaticSendingEnabled = false
            val db = database() ?: return
            withContext(Dispatchers.IO) {
                db.runInTransaction {
                    val pending = db.pendingCommandDao().pending()
                    db.pendingCommandDao().markManualRetry()
                    pending.forEach { command ->
                        db.messageDao().updateLocalState(
                            command.clientMsgId,
                            "MANUAL_RETRY",
                        )
                    }
                }
            }
        }
    }

    suspend fun retryPending(clientMsgId: UUID) {
        val db = database() ?: throw IOException("No local account database is open")
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.pendingCommandDao().markForRetry(clientMsgId.toString())
                db.messageDao().updateLocalState(clientMsgId.toString(), "QUEUED")
            }
        }
        pendingSendScheduler?.invoke()
    }

    suspend fun flushOnlinePending(): PendingFlushResult =
        flushPendingCommands(onlineTransport())

    suspend fun syncLatest(currentUserId: UUID) {
        val db = database() ?: return
        val lastSyncSeq = withContext(Dispatchers.IO) {
            db.syncStateDao().current()?.lastSyncSeq ?: 0L
        }
        val highWatermark = try {
            syncApi.page(lastSyncSeq, null).syncBodyOrThrow().highWatermark
        } catch (exception: SyncResetRequiredException) {
            val highWatermark = syncApi.page(0, 0).syncBodyOrThrow().highWatermark
            fullRestore(currentUserId, highWatermark)
            return
        }
        synchronize(currentUserId, highWatermark)
    }

    internal suspend fun flushPendingCommands(
        transport: MessageSendTransport,
    ): PendingFlushResult = pendingFlushMutex.withLock {
        if (!automaticSendingEnabled) return@withLock PendingFlushResult()
        flushPendingCommandsInternal(transport)
    }

    private suspend fun flushPendingCommandsInternal(
        transport: MessageSendTransport,
    ): PendingFlushResult {
        val db = database() ?: return PendingFlushResult()
        withContext(Dispatchers.IO) { db.pendingCommandDao().resetInFlight() }
        val commands = withContext(Dispatchers.IO) { db.pendingCommandDao().pending() }
        var retryableFailure: MessageSendException? = null
        for (command in commands) {
            val clientMsgId = UUID.fromString(command.clientMsgId)
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    db.pendingCommandDao().markSending(command.clientMsgId)
                    db.messageDao().updateLocalState(command.clientMsgId, "SENDING")
                }
            }
            try {
                val response = transport.send(
                    UUID.fromString(command.conversationId),
                    clientMsgId,
                    command.text,
                )
                upsertAccepted(response)
                withContext(Dispatchers.IO) {
                    db.pendingCommandDao().delete(command.clientMsgId)
                }
            } catch (exception: MessageSendException) {
                withContext(Dispatchers.IO) {
                    db.withTransaction {
                        val changed = db.pendingCommandDao().markPending(command.clientMsgId)
                        if (changed > 0) {
                            db.messageDao().updateLocalState(command.clientMsgId, "QUEUED")
                        }
                    }
                }
                if (!exception.retryable) {
                    withContext(Dispatchers.IO) {
                        db.withTransaction {
                            db.pendingCommandDao().markCommandManualRetry(command.clientMsgId)
                            db.messageDao().updateLocalState(
                                command.clientMsgId,
                                "MANUAL_RETRY",
                            )
                        }
                    }
                } else {
                    retryableFailure = exception
                    break
                }
            } catch (exception: IOException) {
                withContext(Dispatchers.IO) {
                    db.withTransaction {
                        val changed = db.pendingCommandDao().markPending(command.clientMsgId)
                        if (changed > 0) {
                            db.messageDao().updateLocalState(command.clientMsgId, "QUEUED")
                        }
                    }
                }
                retryableFailure = MessageSendException(
                    retryable = true,
                    message = "Message send failed",
                    cause = exception,
                )
                break
            }
        }
        return PendingFlushResult(retryableFailure != null)
    }

    internal data class PendingFlushResult(
        val retryableFailure: Boolean = false,
    )

    private suspend fun sendOnline(
        conversationId: UUID,
        clientMsgId: UUID,
        text: String,
    ): MessageResponse {
        val acknowledgement = CompletableDeferred<MessageResponse>()
        pendingAcks[clientMsgId] = acknowledgement
        if (!webSocket.send(conversationId, clientMsgId, text)) {
            val response = api.send(conversationId, SendMessageRequest(clientMsgId, text))
            if (!response.isSuccessful || response.body() == null) {
                pendingAcks.remove(clientMsgId)
                throw MessageSendException(
                    retryable = response.code() >= 500 || response.code() == 408,
                    message = "Message send failed with HTTP ${response.code()}",
                )
            }
            pendingAcks.remove(clientMsgId)
            return response.body()!!
        } else {
            val accepted = withTimeoutOrNull(10_000) { acknowledgement.await() }
            pendingAcks.remove(clientMsgId)
            if (accepted != null) {
                return accepted
            } else {
                val response = api.send(conversationId, SendMessageRequest(clientMsgId, text))
                if (!response.isSuccessful || response.body() == null) {
                    throw MessageSendException(
                        retryable = response.code() >= 500 || response.code() == 408,
                        message = "Message send failed with HTTP ${response.code()}",
                    )
                }
                return response.body()!!
            }
        }
    }

    internal fun onlineTransport(): MessageSendTransport = object : MessageSendTransport {
        override suspend fun send(
            conversationId: UUID,
            clientMsgId: UUID,
            text: String,
        ): MessageResponse = sendOnline(conversationId, clientMsgId, text)
    }

    fun connect() {
        webSocket.connect()
    }

    fun disconnect() {
        webSocket.disconnect()
    }

    suspend fun apply(event: WireEvent, currentUserId: UUID) {
        val body = event.body ?: return
        val db = database() ?: return
        if (event.operation == "conversation.read") {
            val conversationId = body.conversationId ?: return
            val userId = body.userId ?: return
            val syncSeq = body.syncSeq
            if (syncSeq != null) {
                val lastSyncSeq = withContext(Dispatchers.IO) {
                    db.syncStateDao().current()?.lastSyncSeq ?: 0L
                }
                if (syncSeq <= lastSyncSeq) return
                if (syncSeq > lastSyncSeq + 1) {
                    synchronize(currentUserId, syncSeq)
                    return
                }
            }
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    db.conversationReadStateDao().advance(
                        conversationId.toString(),
                        userId.toString(),
                        body.readSeq ?: 0L,
                    )
                    if (syncSeq != null) {
                        db.syncStateDao().upsert(
                            SyncStateEntity(
                                deviceId = deviceId()?.toString().orEmpty(),
                                lastSyncSeq = syncSeq,
                                lastFullRestoreAt = db.syncStateDao().current()?.lastFullRestoreAt,
                            ),
                        )
                    }
                }
            }
            if (syncSeq != null) {
                syncApi.acknowledge(SyncAckRequest(syncSeq)).syncBodyOrThrow()
            }
            return
        }
        val messageId = body.messageId ?: return
        val conversationId = body.conversationId ?: return
        val senderId = body.senderId ?: return
        val clientMsgId = body.clientMsgId ?: return
        val syncSeq = body.syncSeq
        if (syncSeq != null) {
            val lastSyncSeq = withContext(Dispatchers.IO) {
                db.syncStateDao().current()?.lastSyncSeq ?: 0L
            }
            if (syncSeq <= lastSyncSeq) {
                return
            }
            if (syncSeq > lastSyncSeq + 1) {
                synchronize(currentUserId, syncSeq)
                return
            }
        }
        if (syncSeq != null) {
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    val dao = db.messageDao()
                    val existing = dao.findByClientMsgId(clientMsgId.toString())
                    db.pendingCommandDao().delete(clientMsgId.toString())
                    dao.deleteByClientMsgId(clientMsgId.toString())
                    dao.upsert(
                        LocalMessageEntity(
                            messageId = messageId.toString(),
                            conversationId = conversationId.toString(),
                            senderId = senderId.toString(),
                            clientMsgId = clientMsgId.toString(),
                            conversationSeq = body.conversationSeq,
                            type = body.type ?: "TEXT",
                            state = body.state ?: "ACTIVE",
                            localState = if (senderId == currentUserId) "SENT" else "RECEIVED",
                            text = body.text.orEmpty(),
                            serverAcceptedAt = body.serverAcceptedAt,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                    db.syncStateDao().upsert(
                        SyncStateEntity(
                            deviceId = deviceId()?.toString().orEmpty(),
                            lastSyncSeq = syncSeq,
                            lastFullRestoreAt = db.syncStateDao().current()?.lastFullRestoreAt,
                        ),
                    )
                }
            }
            syncApi.acknowledge(SyncAckRequest(syncSeq)).syncBodyOrThrow()
        } else {
            withContext(Dispatchers.IO) {
                val dao = db.messageDao()
                val existing = dao.findByClientMsgId(clientMsgId.toString())
                db.pendingCommandDao().delete(clientMsgId.toString())
                dao.deleteByClientMsgId(clientMsgId.toString())
                dao.upsert(
                    LocalMessageEntity(
                        messageId = messageId.toString(),
                        conversationId = conversationId.toString(),
                        senderId = senderId.toString(),
                        clientMsgId = clientMsgId.toString(),
                        conversationSeq = body.conversationSeq,
                        type = body.type ?: "TEXT",
                        state = body.state ?: "ACTIVE",
                        localState = if (senderId == currentUserId) "SENT" else "RECEIVED",
                        text = body.text.orEmpty(),
                        serverAcceptedAt = body.serverAcceptedAt,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    ),
                )
            }
        }
        pendingAcks[clientMsgId]?.complete(
            MessageResponse(
                messageId = messageId,
                conversationId = conversationId,
                senderId = senderId,
                clientMsgId = clientMsgId,
                conversationSeq = body.conversationSeq ?: 0,
                type = body.type ?: "TEXT",
                state = body.state ?: "ACTIVE",
                text = body.text.orEmpty(),
                serverAcceptedAt = body.serverAcceptedAt.orEmpty(),
            ),
        )
    }

    private suspend fun applyReadStates(
        page: ReadStatePageResponse,
    ) {
        val db = database() ?: return
        withContext(Dispatchers.IO) {
            db.withTransaction {
                applyReadStatesInTransaction(db, page)
            }
        }
    }

    private fun applyReadStatesInTransaction(
        db: AccountDatabase,
        page: ReadStatePageResponse,
    ) {
        page.states.forEach { state ->
            val existing = db.conversationReadStateDao().find(
                state.conversationId.toString(),
                state.userId.toString(),
            )
            db.conversationReadStateDao().advance(
                state.conversationId.toString(),
                state.userId.toString(),
                ReadSeqPolicy.advance(
                    existing?.readSeq ?: 0L,
                    state.readSeq,
                ),
            )
        }
    }

    private suspend fun upsertAccepted(
        response: MessageResponse,
        currentUserId: UUID? = null,
    ) {
        val db = database() ?: return
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.pendingCommandDao().delete(response.clientMsgId.toString())
                db.messageDao().deleteByClientMsgId(response.clientMsgId.toString())
                db.messageDao().upsert(response.toEntity(
                    localState = if (currentUserId != null && response.senderId != currentUserId) {
                        "RECEIVED"
                    } else {
                        "SENT"
                    },
                ))
            }
        }
    }

    private fun MessageResponse.toEntity(localState: String = "SENT") = LocalMessageEntity(
        messageId = messageId.toString(),
        conversationId = conversationId.toString(),
        senderId = senderId.toString(),
        clientMsgId = clientMsgId.toString(),
        conversationSeq = conversationSeq,
        type = type,
        state = state,
        localState = localState,
        text = text,
        serverAcceptedAt = serverAcceptedAt,
        createdAt = System.currentTimeMillis(),
    )

    private fun <T> Response<T>.messageBodyOrThrow(): T {
        if (isSuccessful && body() != null) return body()!!
        throw IOException("Message request failed with HTTP ${code()}")
    }

    private fun <T> Response<T>.syncBodyOrThrow(): T {
        if (isSuccessful && body() != null) return body()!!
        if (code() == 409) throw SyncResetRequiredException()
        throw IOException("Sync request failed with HTTP ${code()}")
    }

    private fun <T> Response<T>.readBodyOrThrow(): T {
        if (isSuccessful && body() != null) return body()!!
        throw IOException("Read state request failed with HTTP ${code()}")
    }

}
