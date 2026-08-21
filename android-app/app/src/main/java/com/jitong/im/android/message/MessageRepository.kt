package com.jitong.im.android.message

import androidx.room.withTransaction
import com.jitong.im.android.local.AccountDatabase
import com.jitong.im.android.local.EncryptedMediaCache
import com.jitong.im.android.local.LocalConversationReadStateEntity
import com.jitong.im.android.local.LocalConversationEntity
import com.jitong.im.android.local.LocalGroupProfileEntity
import com.jitong.im.android.local.LocalAccountEntity
import com.jitong.im.android.local.LocalMessageEntity
import com.jitong.im.android.local.PendingMessageCommandEntity
import com.jitong.im.android.media.ImageNormalizer
import com.jitong.im.android.local.SyncStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class MessageRepository(
    private val api: MessageApi,
    private val syncApi: SyncApi,
    private val database: () -> AccountDatabase?,
    private val webSocket: MessageWebSocket,
    private val deviceId: () -> UUID? = { null },
    private val mediaApi: com.jitong.im.android.media.MediaApi? = null,
    private val mediaCache: () -> EncryptedMediaCache? = { null },
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
                .filter { it.eventType == "USER_PROFILE_UPDATED" && it.conversationId == null }
                .map { it.entityId }
                .distinct()
                .forEach { profileUserId ->
                    applyUserProfile(syncApi.profile(profileUserId).syncBodyOrThrow())
                }
            page.events
                .filter { it.eventType == "GROUP_PROFILE_UPDATED" && it.conversationId != null }
                .map { it.entityId }
                .distinct()
                .forEach { conversationId ->
                    applyGroupProfile(syncApi.groupProfile(conversationId).syncBodyOrThrow())
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
        applyUserProfile(syncApi.profile(currentUserId).syncBodyOrThrow())
        conversations
            .forEach { conversation ->
                applyConversationSummary(conversation)
                restoreConversation(conversation.conversationId, currentUserId, db)
                applyReadStates(
                    syncApi.readStates(conversation.conversationId).readBodyOrThrow(),
                )
                val groupProfile = syncApi.groupProfile(conversation.conversationId)
                if (groupProfile.isSuccessful && groupProfile.body() != null) {
                    applyGroupProfile(groupProfile.body()!!)
                }
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

    private suspend fun applyConversationSummary(conversation: SyncConversationResponse) {
        val db = database() ?: return
        withContext(Dispatchers.IO) {
            db.conversationDao().upsert(
                LocalConversationEntity(
                    conversationId = conversation.conversationId.toString(),
                    peerUserId = conversation.peerUserId.toString(),
                    peerAccountNo = conversation.peerAccountNo,
                    peerDisplayName = conversation.peerDisplayName,
                    peerAvatarUrl = conversation.avatarUrl,
                    peerAvatarVersion = conversation.avatarVersion,
                    peerAvatarFallback = conversation.avatarFallback,
                    status = conversation.status,
                    relationship = conversation.relationship,
                    lastSequence = 0,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun applyUserProfile(profile: UserProfileResponse) {
        val db = database() ?: return
        mediaCache()?.deleteMatching("avatar-${profile.userId}-v")
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.accountDao().current()
                    ?.takeIf { it.userId == profile.userId.toString() }
                    ?.let { current ->
                        db.accountDao().upsert(
                            current.copy(
                                displayName = profile.displayName,
                                avatarUrl = profile.avatarUrl,
                                avatarVersion = profile.avatarVersion,
                                avatarFallback = profile.avatarFallback,
                            ),
                        )
                    }
                db.conversationDao().updatePeerProfile(
                    profile.userId.toString(),
                    profile.displayName,
                    profile.avatarUrl,
                    profile.avatarVersion,
                    profile.avatarFallback,
                    System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun applyGroupProfile(profile: GroupProfileResponse) {
        val db = database() ?: return
        mediaCache()?.deleteMatching("group-avatar-${profile.conversationId}-v")
        withContext(Dispatchers.IO) {
            db.groupProfileDao().upsert(
                LocalGroupProfileEntity(
                    conversationId = profile.conversationId.toString(),
                    avatarUrl = profile.avatarUrl,
                    avatarVersion = profile.avatarVersion,
                ),
            )
        }
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

    suspend fun sendImage(conversationId: UUID, source: ByteArray): UUID {
        val db = database() ?: throw IOException("No local account database is open")
        val cache = mediaCache() ?: throw IOException("No encrypted media cache is open")
        val normalized = try {
            ImageNormalizer.normalize(source)
        } catch (exception: IllegalArgumentException) {
            throw MessageSendException(retryable = false, message = "Image is invalid")
        } catch (exception: IllegalStateException) {
            throw MessageSendException(retryable = false, message = "Image is invalid")
        }
        val clientMsgId = UUID.randomUUID()
        val uploadId = UUID.randomUUID()
        val createdAt = System.currentTimeMillis()
        val localPath = cache.put("pending-$clientMsgId", normalized)
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.pendingCommandDao().upsert(
                    PendingMessageCommandEntity(
                        clientMsgId = clientMsgId.toString(),
                        conversationId = conversationId.toString(),
                        text = "",
                        createdAt = createdAt,
                        status = SendCommandState.PENDING,
                        type = "IMAGE",
                        uploadId = uploadId.toString(),
                        mediaPath = localPath,
                    ),
                )
                db.messageDao().upsert(
                    LocalMessageEntity(
                        messageId = "local:$clientMsgId",
                        conversationId = conversationId.toString(),
                        senderId = "local",
                        clientMsgId = clientMsgId.toString(),
                        conversationSeq = null,
                        type = "IMAGE",
                        state = "ACTIVE",
                        localState = "QUEUED",
                        text = "",
                        mediaId = null,
                        localMediaPath = localPath,
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

    suspend fun loadMedia(
        message: LocalMessageEntity,
        thumbnail: Boolean,
    ): ByteArray? {
        if (message.state == "RECALLED") {
            deleteMessageMedia(message.mediaId, message.localMediaPath)
            return null
        }
        val cache = mediaCache() ?: return null
        val mediaId = message.mediaId?.let(UUID::fromString)
        if (mediaId == null) return cache.getByPath(message.localMediaPath)
        val cacheName = if (thumbnail) "$mediaId-thumb" else mediaId.toString()
        cache.get(cacheName)?.let { return it }
        val response = mediaApi?.download(
            mediaId = mediaId,
            variant = if (thumbnail) "thumb" else "full",
        ) ?: return null
        if (!response.isSuccessful) return null
        val bytes = response.body()?.bytes() ?: return null
        cache.put(cacheName, bytes)
        return bytes
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
                val conversationId = UUID.fromString(command.conversationId)
                val response = if (command.type == "IMAGE") {
                    val mediaId = uploadPendingImage(command)
                    transport.sendImage(conversationId, clientMsgId, mediaId)
                } else {
                    transport.send(conversationId, clientMsgId, command.text)
                }
                val localMediaPath = upsertAccepted(response)
                mediaCache()?.delete(localMediaPath)
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
            val response = api.send(
                conversationId,
                SendMessageRequest(clientMsgId = clientMsgId, text = text),
            )
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
                val response = api.send(
                    conversationId,
                    SendMessageRequest(clientMsgId = clientMsgId, text = text),
                )
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

        override suspend fun sendImage(
            conversationId: UUID,
            clientMsgId: UUID,
            mediaId: UUID,
        ): MessageResponse = sendOnlineImage(conversationId, clientMsgId, mediaId)
    }

    private suspend fun sendOnlineImage(
        conversationId: UUID,
        clientMsgId: UUID,
        mediaId: UUID,
    ): MessageResponse {
        val acknowledgement = CompletableDeferred<MessageResponse>()
        pendingAcks[clientMsgId] = acknowledgement
        if (!webSocket.sendImage(conversationId, clientMsgId, mediaId)) {
            return sendImageViaHttp(conversationId, clientMsgId, mediaId)
        }
        val accepted = withTimeoutOrNull(10_000) { acknowledgement.await() }
        pendingAcks.remove(clientMsgId)
        return accepted ?: sendImageViaHttp(conversationId, clientMsgId, mediaId)
    }

    private suspend fun sendImageViaHttp(
        conversationId: UUID,
        clientMsgId: UUID,
        mediaId: UUID,
    ): MessageResponse {
        val response = api.send(
            conversationId,
            SendMessageRequest(
                clientMsgId = clientMsgId,
                type = "IMAGE",
                mediaId = mediaId,
            ),
        )
        if (response.isSuccessful && response.body() != null) {
            pendingAcks.remove(clientMsgId)
            return response.body()!!
        }
        pendingAcks.remove(clientMsgId)
        throw MessageSendException(
            retryable = response.code() >= 500 || response.code() == 408,
            message = "Image message send failed with HTTP ${response.code()}",
        )
    }

    private suspend fun uploadPendingImage(
        command: PendingMessageCommandEntity,
    ): UUID {
        val api = mediaApi ?: throw MessageSendException(
            retryable = false,
            message = "Media upload is not configured",
        )
        val cache = mediaCache() ?: throw MessageSendException(
            retryable = false,
            message = "Encrypted media cache is not configured",
        )
        val uploadId = command.uploadId?.let(UUID::fromString) ?: throw MessageSendException(
            retryable = false,
            message = "Image upload identifier is missing",
        )
        val bytes = cache.getByPath(command.mediaPath) ?: throw MessageSendException(
            retryable = false,
            message = "Queued image is no longer available",
        )
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        val response = api.uploadImage(
            uploadId,
            MultipartBody.Part.createFormData("file", "image.bin", body),
        )
        if (response.isSuccessful && response.body() != null) {
            return response.body()!!.mediaId
        }
        throw MessageSendException(
            retryable = response.code() >= 500 || response.code() == 408,
            message = "Image upload failed with HTTP ${response.code()}",
        )
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
        if (event.operation == "user.profile.updated") {
            val profileUserId = body.userId ?: return
            val profileVersion = body.avatarVersion ?: return
            val syncSeq = body.syncSeq ?: return
            val lastSyncSeq = withContext(Dispatchers.IO) {
                db.syncStateDao().current()?.lastSyncSeq ?: 0L
            }
            if (syncSeq <= lastSyncSeq) return
            if (syncSeq != lastSyncSeq + 1) {
                synchronize(currentUserId, syncSeq)
                return
            }
            applyUserProfile(
                UserProfileResponse(
                    profileUserId,
                    body.displayName.orEmpty(),
                    body.avatarUrl,
                    profileVersion,
                    body.avatarFallback ?: body.displayName?.firstOrNull()?.toString() ?: "?"))
            withContext(Dispatchers.IO) {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        deviceId = deviceId()?.toString().orEmpty(),
                        lastSyncSeq = syncSeq,
                        lastFullRestoreAt = db.syncStateDao().current()?.lastFullRestoreAt,
                    ),
                )
            }
            syncApi.acknowledge(SyncAckRequest(syncSeq)).syncBodyOrThrow()
            return
        }
        if (event.operation == "group.profile.updated") {
            val conversationId = body.conversationId ?: return
            val syncSeq = body.syncSeq ?: return
            val lastSyncSeq = withContext(Dispatchers.IO) {
                db.syncStateDao().current()?.lastSyncSeq ?: 0L
            }
            if (syncSeq <= lastSyncSeq) return
            if (syncSeq != lastSyncSeq + 1) {
                synchronize(currentUserId, syncSeq)
                return
            }
            val profile = syncApi.groupProfile(conversationId).syncBodyOrThrow()
            applyGroupProfile(profile)
            withContext(Dispatchers.IO) {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        deviceId = deviceId()?.toString().orEmpty(),
                        lastSyncSeq = syncSeq,
                        lastFullRestoreAt = db.syncStateDao().current()?.lastFullRestoreAt,
                    ),
                )
            }
            syncApi.acknowledge(SyncAckRequest(syncSeq)).syncBodyOrThrow()
            return
        }
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
        if (event.operation == "message.recalled") {
            val messageId = body.messageId ?: return
            val conversationId = body.conversationId ?: return
            val senderId = body.senderId ?: return
            val clientMsgId = body.clientMsgId ?: return
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
            var localMediaPath: String? = null
            var localMediaId: String? = null
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    val existing = db.messageDao().findByClientMsgId(clientMsgId.toString())
                    localMediaPath = existing?.localMediaPath
                    localMediaId = existing?.mediaId
                    db.pendingCommandDao().delete(clientMsgId.toString())
                    db.messageDao().deleteByClientMsgId(clientMsgId.toString())
                    db.messageDao().upsert(
                        LocalMessageEntity(
                            messageId = messageId.toString(),
                            conversationId = conversationId.toString(),
                            senderId = senderId.toString(),
                            clientMsgId = clientMsgId.toString(),
                            conversationSeq = body.conversationSeq,
                            type = body.type ?: existing?.type ?: "TEXT",
                            state = "RECALLED",
                            localState = if (senderId == currentUserId) "SENT" else "RECEIVED",
                            text = "",
                            mediaId = null,
                            localMediaPath = null,
                            serverAcceptedAt = body.serverAcceptedAt ?: existing?.serverAcceptedAt,
                            recalledAt = body.recalledAt,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        ),
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
            deleteMessageMedia(body.mediaId?.toString() ?: localMediaId, localMediaPath)
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
            val localMediaPath = withContext(Dispatchers.IO) {
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
                            mediaId = body.mediaId?.toString(),
                            localMediaPath = null,
                            serverAcceptedAt = body.serverAcceptedAt,
                            recalledAt = body.recalledAt,
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
                    existing?.localMediaPath
                }
            }
            if (body.mediaId != null) mediaCache()?.delete(localMediaPath)
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
                        mediaId = body.mediaId?.toString(),
                        localMediaPath = existing?.localMediaPath,
                        serverAcceptedAt = body.serverAcceptedAt,
                        recalledAt = body.recalledAt,
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
                mediaId = body.mediaId,
                serverAcceptedAt = body.serverAcceptedAt.orEmpty(),
                recalledAt = body.recalledAt,
            ),
        )
    }

    suspend fun recall(message: LocalMessageEntity) {
        val response = api.recall(UUID.fromString(message.messageId))
        if (!response.isSuccessful || response.body() == null) {
            throw IOException("Message recall failed with HTTP ${response.code()}")
        }
        val recalled = response.body()!!
        apply(
            WireEvent(
                version = 1,
                operation = "message.recalled",
                requestId = null,
                body = WireMessageBody(
                    messageId = recalled.messageId,
                    conversationId = recalled.conversationId,
                    senderId = recalled.senderId,
                    clientMsgId = recalled.clientMsgId,
                    conversationSeq = recalled.conversationSeq,
                    type = recalled.type,
                    state = recalled.state,
                    text = recalled.text,
                    mediaId = recalled.mediaId,
                    serverAcceptedAt = recalled.serverAcceptedAt,
                    recalledAt = recalled.recalledAt,
                    syncSeq = null,
                    deviceId = null,
                    deviceClass = null,
                    highWatermark = null,
                    userId = null,
                    readSeq = null,
                    displayName = null,
                    avatarUrl = null,
                    avatarVersion = null,
                    avatarFallback = null,
                ),
            ),
            message.senderId.let { UUID.fromString(it) },
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
    ): String? {
        val db = database() ?: return null
        return withContext(Dispatchers.IO) {
            db.withTransaction {
                val existing = db.messageDao().findByClientMsgId(response.clientMsgId.toString())
                db.pendingCommandDao().delete(response.clientMsgId.toString())
                db.messageDao().deleteByClientMsgId(response.clientMsgId.toString())
                db.messageDao().upsert(response.toEntity(
                    localState = if (currentUserId != null && response.senderId != currentUserId) {
                        "RECEIVED"
                    } else {
                        "SENT"
                    },
                    localMediaPath = null,
                ))
                existing?.localMediaPath
            }
        }
    }

    private fun deleteMessageMedia(
        mediaId: String?,
        localMediaPath: String?,
    ) {
        val cache = mediaCache() ?: return
        mediaId?.let {
            cache.delete(it)
            cache.delete("$it-thumb")
        }
        cache.delete(localMediaPath)
    }

    private fun MessageResponse.toEntity(
        localState: String = "SENT",
        localMediaPath: String? = null,
    ) = LocalMessageEntity(
        messageId = messageId.toString(),
        conversationId = conversationId.toString(),
        senderId = senderId.toString(),
        clientMsgId = clientMsgId.toString(),
        conversationSeq = conversationSeq,
        type = type,
        state = state,
        localState = localState,
        text = text.orEmpty(),
        mediaId = mediaId?.toString(),
        localMediaPath = localMediaPath,
        serverAcceptedAt = serverAcceptedAt,
        recalledAt = recalledAt,
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
