package com.jitong.im.android.message

import androidx.room.withTransaction
import com.jitong.im.android.local.AccountDatabase
import com.jitong.im.android.local.LocalMessageEntity
import com.jitong.im.android.local.SyncStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
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
                dao.deleteByClientMsgId(it.clientMsgId.toString())
                dao.upsert(it.toEntity())
            }
        }
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
            val nextAfter = page.nextAfterSeq
            if (nextAfter <= afterSeq && page.hasMore) {
                throw IOException("Sync cursor did not advance")
            }
            afterSeq = nextAfter
            if (!page.hasMore && afterSeq < requestedUntil) {
                throw IOException("Sync ended before the requested high watermark")
            }
        }
        withContext(Dispatchers.IO) {
            db.withTransaction {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    deviceId = deviceId()?.toString().orEmpty(),
                    lastSyncSeq = requestedUntil,
                    lastFullRestoreAt = db.syncStateDao().current()?.lastFullRestoreAt,
                ),
            )
            }
        }
        syncApi.acknowledge(SyncAckRequest(requestedUntil)).syncBodyOrThrow()
    }

    private suspend fun fullRestore(currentUserId: UUID, highWatermark: Long) {
        val db = database() ?: return
        val conversations = syncApi.conversations().syncBodyOrThrow()
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.messageDao().clearAll()
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
    ) {
        var afterConversationSeq = 0L
        while (true) {
            val history = api.history(conversationId, afterConversationSeq, 200).messageBodyOrThrow()
            if (history.messages.isEmpty()) {
                return
            }
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    val dao = db.messageDao()
                    history.messages.forEach {
                        dao.deleteByClientMsgId(it.clientMsgId.toString())
                        dao.upsert(it.toEntity(
                            localState = if (it.senderId == currentUserId) "SENT" else "RECEIVED",
                        ))
                    }
                }
            }
            val nextSequence = history.messages.last().conversationSeq
            if (nextSequence <= afterConversationSeq || history.messages.size < 200) {
                return
            }
            afterConversationSeq = nextSequence
        }
    }

    suspend fun send(conversationId: UUID, text: String): UUID {
        require(text.isNotBlank()) { "Message text must not be blank" }
        val db = database() ?: throw IOException("No local account database is open")
        val clientMsgId = UUID.randomUUID()
        withContext(Dispatchers.IO) {
            db.messageDao().upsert(
                LocalMessageEntity(
                    messageId = "local:$clientMsgId",
                    conversationId = conversationId.toString(),
                    senderId = "local",
                    clientMsgId = clientMsgId.toString(),
                    conversationSeq = null,
                    type = "TEXT",
                    state = "ACTIVE",
                    localState = "SENDING",
                    text = text,
                    serverAcceptedAt = null,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        val acknowledgement = CompletableDeferred<MessageResponse>()
        pendingAcks[clientMsgId] = acknowledgement
        if (!webSocket.send(conversationId, clientMsgId, text)) {
            val response = api.send(conversationId, SendMessageRequest(clientMsgId, text))
            if (!response.isSuccessful || response.body() == null) {
                pendingAcks.remove(clientMsgId)
                throw IOException("Message send failed with HTTP ${response.code()}")
            }
            upsertAccepted(response.body()!!)
            pendingAcks.remove(clientMsgId)
        } else {
            val accepted = withTimeoutOrNull(10_000) { acknowledgement.await() }
            pendingAcks.remove(clientMsgId)
            if (accepted == null) {
                val response = api.send(conversationId, SendMessageRequest(clientMsgId, text))
                if (!response.isSuccessful || response.body() == null) {
                    throw IOException("Message send failed with HTTP ${response.code()}")
                }
                upsertAccepted(response.body()!!)
            }
        }
        return clientMsgId
    }

    fun connect() {
        webSocket.connect()
    }

    fun disconnect() {
        webSocket.disconnect()
    }

    suspend fun apply(event: WireEvent, currentUserId: UUID) {
        val body = event.body ?: return
        val messageId = body.messageId ?: return
        val conversationId = body.conversationId ?: return
        val senderId = body.senderId ?: return
        val clientMsgId = body.clientMsgId ?: return
        val db = database() ?: return
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

    private suspend fun upsertAccepted(
        response: MessageResponse,
        currentUserId: UUID? = null,
    ) {
        val dao = database()?.messageDao() ?: return
        withContext(Dispatchers.IO) {
            dao.deleteByClientMsgId(response.clientMsgId.toString())
            dao.upsert(response.toEntity(
                localState = if (currentUserId != null && response.senderId != currentUserId) {
                    "RECEIVED"
                } else {
                    "SENT"
                },
            ))
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

}
