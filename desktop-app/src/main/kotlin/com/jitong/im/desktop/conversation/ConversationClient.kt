package com.jitong.im.desktop.conversation

import com.jitong.im.desktop.local.LocalConversation
import com.jitong.im.desktop.local.LocalDatabase
import com.jitong.im.desktop.local.LocalMessage
import com.jitong.im.desktop.local.LocalReadState
import com.jitong.im.desktop.local.LocalGroupProfile
import com.jitong.im.desktop.media.ImageNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

@Serializable
data class DesktopConversationSummary(
    val version: Int,
    val conversationId: String,
    val peerUserId: String,
    val peerAccountNo: String,
    val peerDisplayName: String,
    val status: String,
    val relationship: String,
    val blockedByMe: Boolean,
    val readSeq: Long,
    val peerReadSeq: Long,
    val avatarUrl: String? = null,
    val avatarVersion: Long = 0,
    val avatarFallback: String = "?",
    val searchVisible: Boolean = true,
    val searchVisibleAfterSeq: Long = 0,
)

@Serializable
data class DesktopContactSearchResult(
    val version: Int,
    val accountNo: String,
    val displayName: String,
    val avatarUrl: String?,
    val avatarVersion: Long = 0,
    val avatarFallback: String = "?",
    val relationship: String,
    val pendingRequestId: String?,
)

@Serializable
data class DesktopCreateContactRequest(
    val accountNo: String,
    val verification: String = "",
)

@Serializable
data class DesktopContactRequest(
    val version: Int,
    val requestId: String,
    val requesterId: String,
    val recipientId: String,
    val status: String,
    val verification: String,
    val expiresAt: String,
    val conversationId: String?,
)

@Serializable
data class DesktopContactRequestSummary(
    val version: Int,
    val requestId: String,
    val requesterId: String,
    val recipientId: String,
    val status: String,
    val verification: String,
    val expiresAt: String,
    val incoming: Boolean,
    val peerAccountNo: String,
    val peerDisplayName: String,
)

@Serializable
data class DesktopContactSummary(
    val version: Int,
    val userId: String,
    val accountNo: String,
    val displayName: String,
    val conversationId: String,
    val relationship: String,
    val avatarUrl: String? = null,
    val avatarVersion: Long = 0,
    val avatarFallback: String = "?",
)

@Serializable
data class DesktopUserProfile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val avatarVersion: Long = 0,
    val avatarFallback: String = "?",
)

@Serializable
data class DesktopAvatarUploadResponse(
    val version: Int,
    val mediaId: String,
    val purpose: String,
    val state: String,
    val contentType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val avatarVersion: Long,
    val thumbnailUrl: String,
)

data class DesktopAvatarCrop(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

@Serializable
data class DesktopMediaUploadResponse(
    val version: Int,
    val mediaId: String,
    val purpose: String,
    val state: String,
    val contentType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
)

@Serializable
data class DesktopGroupProfile(
    val conversationId: String,
    val avatarUrl: String? = null,
    val avatarVersion: Long = 0,
)

@Serializable
data class DesktopSyncEvent(
    val syncSeq: Long,
    val eventType: String,
    val entityId: String,
    val conversationId: String?,
    val createdAt: String,
)

@Serializable
data class DesktopSyncPage(
    val version: Int,
    val afterSeq: Long,
    val highWatermark: Long,
    val untilSeq: Long,
    val nextAfterSeq: Long,
    val hasMore: Boolean,
    val events: List<DesktopSyncEvent>,
)

@Serializable
data class DesktopSyncAckRequest(val syncSeq: Long)

@Serializable
data class DesktopReadState(
    val conversationId: String,
    val userId: String,
    val readSeq: Long,
)

@Serializable
data class DesktopReadStatePage(
    val version: Int,
    val conversationId: String,
    val states: List<DesktopReadState>,
)

@Serializable
data class DesktopReadStateRequest(val readSeq: Long)

@Serializable
data class DesktopMessage(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val clientMsgId: String,
    val conversationSeq: Long,
    val type: String,
    val state: String,
    val text: String? = null,
    val mediaId: String? = null,
    val serverAcceptedAt: String,
    val recalledAt: String? = null,
    val systemEventType: String? = null,
    val systemTargetUserId: String? = null,
    val systemRole: String? = null,
    val moderatedByUserId: String? = null,
    val moderatedReason: String? = null,
    val moderatedAt: String? = null,
)

@Serializable
data class DesktopMessagePage(
    val version: Int,
    val conversationId: String,
    val messages: List<DesktopMessage>,
)

@Serializable
data class DesktopSendMessageRequest(
    val clientMsgId: String,
    val type: String = "TEXT",
    val text: String? = null,
    val mediaId: String? = null,
)

@Serializable
data class DesktopRealtimeEnvelope(
    val version: Int,
    val operation: String,
    val requestId: String?,
    val body: DesktopRealtimeBody?,
)

@Serializable
data class DesktopRealtimeBody(
    val messageId: String? = null,
    val conversationId: String? = null,
    val senderId: String? = null,
    val clientMsgId: String? = null,
    val conversationSeq: Long? = null,
    val type: String? = null,
    val state: String? = null,
    val text: String? = null,
    val mediaId: String? = null,
    val serverAcceptedAt: String? = null,
    val recalledAt: String? = null,
    val systemEventType: String? = null,
    val systemTargetUserId: String? = null,
    val systemRole: String? = null,
    val moderatedByUserId: String? = null,
    val moderatedReason: String? = null,
    val moderatedAt: String? = null,
    val syncSeq: Long? = null,
    val userId: String? = null,
    val displayName: String? = null,
    val readSeq: Long? = null,
    val highWatermark: Long? = null,
    val code: String? = null,
    val message: String? = null,
    val avatarUrl: String? = null,
    val avatarVersion: Long? = null,
    val avatarFallback: String? = null,
    val deviceId: String? = null,
    val deviceClass: String? = null,
)

class ConversationClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val jsonMediaType = "application/json".toMediaType()

    fun search(accessToken: String, accountNo: String): DesktopContactSearchResult =
        requestJson(get("/api/v1/users/search?accountNo=$accountNo", accessToken))

    fun createContactRequest(
        accessToken: String,
        accountNo: String,
        verification: String = "",
    ): DesktopContactRequest =
        requestJson(
            post(
                path = "/api/v1/contact-requests",
                accessToken = accessToken,
                body = DesktopCreateContactRequest(accountNo, verification)))

    fun listContactRequests(accessToken: String): List<DesktopContactRequestSummary> =
        requestJson(get("/api/v1/contact-requests", accessToken))

    fun acceptContactRequest(accessToken: String, requestId: String): DesktopContactRequest =
        requestJson(post("/api/v1/contact-requests/$requestId/accept", accessToken))

    fun rejectContactRequest(accessToken: String, requestId: String): DesktopContactRequest =
        requestJson(post("/api/v1/contact-requests/$requestId/reject", accessToken))

    fun cancelContactRequest(accessToken: String, requestId: String): DesktopContactRequest =
        requestJson(post("/api/v1/contact-requests/$requestId/cancel", accessToken))

    fun listContacts(accessToken: String): List<DesktopContactSummary> =
        requestJson(get("/api/v1/contacts", accessToken))

    fun removeContact(accessToken: String, userId: String) {
        execute(requestWithoutBody("/api/v1/contacts/$userId", accessToken, "DELETE"))
    }

    fun block(accessToken: String, userId: String) {
        execute(post("/api/v1/blocks/$userId", accessToken))
    }

    fun unblock(accessToken: String, userId: String) {
        execute(requestWithoutBody("/api/v1/blocks/$userId", accessToken, "DELETE"))
    }

    fun list(accessToken: String): List<DesktopConversationSummary> =
        requestJson(get("/api/v1/conversations", accessToken))

    fun userProfile(accessToken: String, userId: String): DesktopUserProfile =
        requestJson(get("/api/v1/users/$userId/profile", accessToken))

    fun replaceUserAvatar(
        accessToken: String,
        fileName: String,
        content: ByteArray,
        crop: DesktopAvatarCrop? = null,
    ): DesktopAvatarUploadResponse {
        val uploadId = UUID.randomUUID()
        val path = buildString {
            append("/api/v1/users/me/avatar?uploadId=")
            append(uploadId)
            crop?.let {
                append("&cropX=${it.x}")
                append("&cropY=${it.y}")
                append("&cropWidth=${it.width}")
                append("&cropHeight=${it.height}")
            }
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                content.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer $accessToken")
            .put(body)
            .build()
        return requestJson(request)
    }

    fun uploadImage(
        accessToken: String,
        fileName: String,
        content: ByteArray,
    ): DesktopMediaUploadResponse {
        val uploadId = UUID.randomUUID()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                ImageNormalizer.normalize(content)
                    .toRequestBody("image/jpeg".toMediaType()))
            .build()
        return requestJson(
            Request.Builder()
                .url(url("/api/v1/media/images?uploadId=$uploadId"))
                .header("Authorization", "Bearer $accessToken")
                .post(body)
                .build())
    }

    fun removeUserAvatar(accessToken: String) {
        execute(requestWithoutBody("/api/v1/users/me/avatar", accessToken, "DELETE"))
    }

    fun deriveSquareCrop(content: ByteArray): DesktopAvatarCrop? {
        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(content))
            ?: return null
        val side = minOf(image.width, image.height)
        return DesktopAvatarCrop(
            x = (image.width - side) / 2,
            y = (image.height - side) / 2,
            width = side,
            height = side)
    }

    fun restoreConversation(
        accessToken: String,
        local: LocalDatabase,
        conversationId: String,
        currentUserId: String,
    ) {
        var afterSequence = local.lastConversationSeq(conversationId)
        while (true) {
            val page = history(accessToken, conversationId, afterSequence)
            if (page.messages.isEmpty()) return
            page.messages.forEach { applyMessage(local, it, currentUserId) }
            val nextSequence = page.messages.last().conversationSeq
            if (nextSequence <= afterSequence || page.messages.size < 200) return
            afterSequence = nextSequence
        }
    }

    fun restoreReadStates(
        accessToken: String,
        local: LocalDatabase,
        conversationId: String,
    ) {
        readStates(accessToken, conversationId).states.forEach {
            applyReadState(local, it)
        }
    }

    fun fullRestore(
        accessToken: String,
        local: LocalDatabase,
        currentUserId: String,
        highWatermark: Long,
    ) {
        require(highWatermark >= 0) { "highWatermark must not be negative" }
        val conversations = list(accessToken)
        local.clearMessageData()
        local.saveLastSyncSeq(0)
        conversations.forEach { conversation ->
            applyConversation(local, conversation)
            restoreConversation(
                accessToken,
                local,
                conversation.conversationId,
                currentUserId)
            restoreReadStates(accessToken, local, conversation.conversationId)
        }
        restoreGroupProfiles(accessToken, local, conversations.map { it.conversationId })
        acknowledge(accessToken, highWatermark)
        local.saveLastSyncSeq(highWatermark)
    }

    fun sync(
        accessToken: String,
        afterSeq: Long,
        untilSeq: Long? = null,
    ): DesktopSyncPage {
        val query = buildString {
            append("?after=")
            append(afterSeq)
            untilSeq?.let {
                append("&until=")
                append(it)
            }
            append("&limit=200")
        }
        return requestJson(get("/api/v1/sync$query", accessToken))
    }

    fun applySyncProfileEvents(
        accessToken: String,
        local: LocalDatabase,
        events: List<DesktopSyncEvent>,
    ) {
        events
            .filter { it.eventType == "USER_PROFILE_UPDATED" }
            .map { it.entityId }
            .distinct()
            .forEach { userId ->
                val profile = requestJson<DesktopUserProfile>(
                    get("/api/v1/users/$userId/profile", accessToken))
                local.updatePeerProfile(
                    userId = profile.userId,
                    displayName = profile.displayName,
                    avatarUrl = profile.avatarUrl,
                    avatarVersion = profile.avatarVersion,
                    avatarFallback = profile.avatarFallback)
                local.mediaCache().deleteMatching("avatar-$userId-v")
            }
        events
            .filter { it.eventType == "GROUP_PROFILE_UPDATED" && it.conversationId != null }
            .map { it.conversationId!! }
            .distinct()
            .forEach { conversationId ->
                val profile = requestJson<DesktopGroupProfile>(
                    get("/api/v1/groups/$conversationId/profile", accessToken))
                local.upsertGroupProfile(
                    LocalGroupProfile(
                        conversationId = profile.conversationId,
                        avatarUrl = profile.avatarUrl,
                        avatarVersion = profile.avatarVersion))
                local.mediaCache().deleteMatching("group-avatar-$conversationId-v")
            }
    }

    fun restoreGroupProfiles(
        accessToken: String,
        local: LocalDatabase,
        conversationIds: List<String>,
    ) {
        conversationIds.distinct().forEach { conversationId ->
            val profile = requestJsonOrNull<DesktopGroupProfile>(
                get("/api/v1/groups/$conversationId/profile", accessToken))
                    ?: return@forEach
            local.upsertGroupProfile(
                LocalGroupProfile(
                    conversationId = profile.conversationId,
                    avatarUrl = profile.avatarUrl,
                    avatarVersion = profile.avatarVersion))
            local.mediaCache().deleteMatching("group-avatar-$conversationId-v")
        }
    }

    fun acknowledge(accessToken: String, syncSeq: Long) {
        execute(
            post(
                path = "/api/v1/sync/ack",
                accessToken = accessToken,
                body = DesktopSyncAckRequest(syncSeq)))
    }

    fun history(
        accessToken: String,
        conversationId: String,
        afterSeq: Long,
    ): DesktopMessagePage =
        requestJson(
            get(
                "/api/v1/conversations/$conversationId/messages" +
                    "?afterSeq=$afterSeq&limit=200",
                accessToken))

    fun sendMessage(
        accessToken: String,
        conversationId: String,
        clientMsgId: String,
        text: String,
    ): DesktopMessage =
        requestJson(
            post(
                path = "/api/v1/conversations/$conversationId/messages",
                accessToken = accessToken,
                body = DesktopSendMessageRequest(
                    clientMsgId = clientMsgId,
                    text = text)))

    fun sendImage(
        accessToken: String,
        conversationId: String,
        clientMsgId: String,
        mediaId: String,
    ): DesktopMessage =
        requestJson(
            post(
                path = "/api/v1/conversations/$conversationId/messages",
                accessToken = accessToken,
                body = DesktopSendMessageRequest(
                    clientMsgId = clientMsgId,
                    type = "IMAGE",
                    mediaId = mediaId)))

    fun recallMessage(accessToken: String, messageId: String): DesktopMessage =
        requestJson(post("/api/v1/messages/$messageId/recall", accessToken))

    fun readStates(accessToken: String, conversationId: String): DesktopReadStatePage =
        requestJson(get("/api/v1/conversations/$conversationId/read", accessToken))

    fun markRead(
        accessToken: String,
        conversationId: String,
        readSeq: Long,
    ): DesktopReadStatePage =
        requestJson(
            post(
                path = "/api/v1/conversations/$conversationId/read",
                accessToken = accessToken,
                body = DesktopReadStateRequest(readSeq)))

    fun applyConversation(local: LocalDatabase, conversation: DesktopConversationSummary) {
        local.upsertConversation(
            LocalConversation(
                conversationId = conversation.conversationId,
                peerUserId = conversation.peerUserId,
                peerAccountNo = conversation.peerAccountNo,
                peerDisplayName = conversation.peerDisplayName,
                peerAvatarUrl = conversation.avatarUrl,
                peerAvatarVersion = conversation.avatarVersion,
                peerAvatarFallback = conversation.avatarFallback,
                status = conversation.status,
                relationship = conversation.relationship,
                blockedByMe = conversation.blockedByMe,
                readSeq = conversation.readSeq,
                peerReadSeq = conversation.peerReadSeq,
                searchVisible = conversation.searchVisible,
                searchVisibleAfterSeq = conversation.searchVisibleAfterSeq,
                updatedAt = System.currentTimeMillis()))
    }

    fun applyReadState(local: LocalDatabase, state: DesktopReadState) {
        val existing = local.readState(state.conversationId, state.userId)
        if (state.readSeq >= existing) {
            local.upsertReadState(
                LocalReadState(
                    conversationId = state.conversationId,
                    userId = state.userId,
                    readSeq = state.readSeq))
            local.listConversations()
                .firstOrNull { it.conversationId == state.conversationId }
                ?.let { conversation ->
                    if (conversation.peerUserId == state.userId) {
                        local.updateConversationReadProgress(
                            state.conversationId,
                            conversation.readSeq,
                            state.readSeq)
                    } else {
                        local.updateConversationReadProgress(
                            state.conversationId,
                            state.readSeq,
                            conversation.peerReadSeq)
                    }
                }
        }
    }

    fun loadUserAvatar(
        accessToken: String,
        local: LocalDatabase,
        userId: String,
        avatarVersion: Long,
    ): ByteArray? {
        if (avatarVersion <= 0) return null
        val cacheName = "avatar-$userId-v$avatarVersion"
        local.mediaCache().getOrNull(cacheName)?.let { return it }
        local.mediaCache().deleteMatching("avatar-$userId-v", cacheName)
        httpClient.newCall(
            get(
                "/api/v1/users/$userId/avatar?variant=thumb&avatarVersion=$avatarVersion",
                accessToken)).execute().use { response ->
            if (response.code == 404 || response.code == 410) return null
            if (!response.isSuccessful) {
                throw ConversationApiException(response.code, response.body?.string().orEmpty())
            }
            val bytes = response.body?.bytes() ?: return null
            local.mediaCache().put(cacheName, bytes)
            return bytes
        }
    }

    fun loadGroupAvatar(
        accessToken: String,
        local: LocalDatabase,
        conversationId: String,
        avatarVersion: Long,
    ): ByteArray? {
        if (avatarVersion <= 0) return null
        val cacheName = "group-avatar-$conversationId-v$avatarVersion"
        local.mediaCache().getOrNull(cacheName)?.let { return it }
        local.mediaCache().deleteMatching("group-avatar-$conversationId-v", cacheName)
        httpClient.newCall(
            get(
                "/api/v1/groups/$conversationId/avatar" +
                    "?variant=thumb&avatarVersion=$avatarVersion",
                accessToken)).execute().use { response ->
            if (response.code == 404 || response.code == 410) return null
            if (!response.isSuccessful) {
                throw ConversationApiException(response.code, response.body?.string().orEmpty())
            }
            val bytes = response.body?.bytes() ?: return null
            local.mediaCache().put(cacheName, bytes)
            return bytes
        }
    }

    fun currentGroupAvatar(
        accessToken: String,
        local: LocalDatabase,
        conversationId: String,
    ): ByteArray? {
        val profile = local.groupProfile(conversationId) ?: return null
        return loadGroupAvatar(accessToken, local, conversationId, profile.avatarVersion)
    }

    fun replaceConversations(
        local: LocalDatabase,
        conversations: List<DesktopConversationSummary>,
    ) {
        local.replaceConversations(
            conversations.map {
                LocalConversation(
                    conversationId = it.conversationId,
                    peerUserId = it.peerUserId,
                    peerAccountNo = it.peerAccountNo,
                    peerDisplayName = it.peerDisplayName,
                    peerAvatarUrl = it.avatarUrl,
                    peerAvatarVersion = it.avatarVersion,
                    peerAvatarFallback = it.avatarFallback,
                    status = it.status,
                    relationship = it.relationship,
                    blockedByMe = it.blockedByMe,
                    readSeq = it.readSeq,
                    peerReadSeq = it.peerReadSeq,
                    searchVisible = it.searchVisible,
                    searchVisibleAfterSeq = it.searchVisibleAfterSeq,
                    updatedAt = System.currentTimeMillis())
            })
    }

    fun applyMessage(
        local: LocalDatabase,
        message: DesktopMessage,
        currentUserId: String,
    ) {
        if (message.state == "RECALLED" || message.state == "MODERATED") {
            applyRecalledMessage(local, message, currentUserId)
            return
        }
        local.replaceMessageByClientId(
            LocalMessage(
                messageId = message.messageId,
                conversationId = message.conversationId,
                senderId = message.senderId,
                clientMsgId = message.clientMsgId,
                conversationSeq = message.conversationSeq,
                type = message.type,
                state = message.state,
                localState = if (message.senderId == currentUserId) "SENT" else "RECEIVED",
                text = message.text.orEmpty(),
                mediaId = message.mediaId,
                serverAcceptedAt = message.serverAcceptedAt,
                recalledAt = message.recalledAt,
                systemEventType = message.systemEventType,
                systemTargetUserId = message.systemTargetUserId,
                systemRole = message.systemRole,
                moderatedByUserId = message.moderatedByUserId,
                moderatedReason = message.moderatedReason,
                moderatedAt = message.moderatedAt,
                createdAt = System.currentTimeMillis()))
    }

    fun applyRecalledMessage(
        local: LocalDatabase,
        message: DesktopMessage,
        currentUserId: String,
    ) {
        val previous = local.findMessageByClientId(
            message.conversationId,
            message.clientMsgId)
        val previousMediaId = previous?.mediaId
        local.replaceMessageByClientId(
            LocalMessage(
                messageId = message.messageId,
                conversationId = message.conversationId,
                senderId = message.senderId,
                clientMsgId = message.clientMsgId,
                conversationSeq = message.conversationSeq,
                type = message.type,
                state = message.state,
                localState = if (message.senderId == currentUserId) "SENT" else "RECEIVED",
                text = "",
                mediaId = null,
                serverAcceptedAt = message.serverAcceptedAt
                    .ifBlank { previous?.serverAcceptedAt },
                recalledAt = message.recalledAt,
                systemEventType = message.systemEventType,
                systemTargetUserId = message.systemTargetUserId,
                systemRole = message.systemRole,
                moderatedByUserId = message.moderatedByUserId,
                moderatedReason = message.moderatedReason,
                moderatedAt = message.moderatedAt,
                createdAt = previous?.createdAt ?: System.currentTimeMillis()))
        local.deleteMessageMediaCache(previousMediaId ?: message.mediaId)
    }

    fun newPendingMessage(
        local: LocalDatabase,
        conversationId: String,
        currentUserId: String,
        clientMsgId: String,
        text: String,
    ) {
        local.upsertMessage(
            LocalMessage(
                messageId = "local:$clientMsgId",
                conversationId = conversationId,
                senderId = currentUserId,
                clientMsgId = clientMsgId,
                conversationSeq = null,
                type = "TEXT",
                state = "ACTIVE",
                localState = "SENDING",
                text = text,
                mediaId = null,
                serverAcceptedAt = null,
                createdAt = System.currentTimeMillis()))
    }

    fun newPendingImage(
        local: LocalDatabase,
        conversationId: String,
        currentUserId: String,
        clientMsgId: String,
        mediaId: String,
    ) {
        local.upsertMessage(
            LocalMessage(
                messageId = "local:$clientMsgId",
                conversationId = conversationId,
                senderId = currentUserId,
                clientMsgId = clientMsgId,
                conversationSeq = null,
                type = "IMAGE",
                state = "ACTIVE",
                localState = "SENDING",
                text = "",
                mediaId = mediaId,
                serverAcceptedAt = null,
                createdAt = System.currentTimeMillis()))
    }

    fun applyRealtime(
        local: LocalDatabase,
        envelope: DesktopRealtimeEnvelope,
        currentUserId: String,
    ) {
        val body = envelope.body ?: return
        val syncSeq = body.syncSeq
        if (syncSeq != null) {
            val lastSyncSeq = local.lastSyncSeq()
            if (syncSeq <= lastSyncSeq) return
            if (syncSeq != lastSyncSeq + 1) {
                throw SyncGapException(lastSyncSeq, syncSeq)
            }
        }
        if (envelope.operation == "conversation.read") {
            val conversationId = body.conversationId ?: return
            val userId = body.userId ?: return
            val readSeq = body.readSeq ?: return
            applyReadState(local, DesktopReadState(conversationId, userId, readSeq))
            syncSeq?.let(local::saveLastSyncSeq)
            return
        }
        if (envelope.operation == "user.profile.updated") {
            val userId = body.userId ?: return
            local.mediaCache().deleteMatching("avatar-$userId-v")
            local.updatePeerProfile(
                userId,
                body.displayName.orEmpty(),
                body.avatarUrl,
                body.avatarVersion ?: 0,
                body.avatarFallback ?: body.displayName?.firstOrNull()?.toString() ?: "?")
            syncSeq?.let(local::saveLastSyncSeq)
            return
        }
        if (envelope.operation == "group.profile.updated") {
            val conversationId = body.conversationId ?: return
            local.upsertGroupProfile(
                LocalGroupProfile(
                    conversationId = conversationId,
                    avatarUrl = body.avatarUrl,
                    avatarVersion = body.avatarVersion ?: 0))
            local.mediaCache().deleteMatching("group-avatar-$conversationId-v")
            syncSeq?.let(local::saveLastSyncSeq)
            return
        }
        if (envelope.operation == "message.recalled" || envelope.operation == "message.moderated") {
            val message = body.toMessage() ?: return
            applyRecalledMessage(local, message, currentUserId)
            syncSeq?.let(local::saveLastSyncSeq)
            return
        }
        if (envelope.operation == "error") {
            throw RealtimeCommandException(
                code = body.code ?: "REALTIME_ERROR",
                message = body.message ?: "The realtime command failed")
        }
        if (envelope.operation != "message.created" && envelope.operation != "message.ack") {
            return
        }
        val message = body.toMessage() ?: return
        applyMessage(local, message, currentUserId)
        syncSeq?.let(local::saveLastSyncSeq)
    }

    private fun DesktopRealtimeBody.toMessage(): DesktopMessage? {
        val messageId = messageId ?: return null
        val conversationId = conversationId ?: return null
        val senderId = senderId ?: return null
        val clientMsgId = clientMsgId ?: return null
        val conversationSeq = conversationSeq ?: return null
        return DesktopMessage(
            messageId = messageId,
            conversationId = conversationId,
            senderId = senderId,
            clientMsgId = clientMsgId,
            conversationSeq = conversationSeq,
            type = type ?: "TEXT",
            state = state ?: "ACTIVE",
            text = text,
            mediaId = mediaId,
            serverAcceptedAt = serverAcceptedAt.orEmpty(),
            recalledAt = recalledAt,
            systemEventType = systemEventType,
            systemTargetUserId = systemTargetUserId,
            systemRole = systemRole,
            moderatedByUserId = moderatedByUserId,
            moderatedReason = moderatedReason,
            moderatedAt = moderatedAt)
    }

    fun loadMedia(
        accessToken: String,
        local: LocalDatabase,
        message: LocalMessage,
        thumbnail: Boolean,
    ): ByteArray? {
        if (message.type != "IMAGE" || message.state != "ACTIVE") return null
        val mediaId = message.mediaId ?: return null
        val cacheName = if (thumbnail) {
            "message-media-$mediaId-thumb"
        } else {
            "message-media-$mediaId"
        }
        if (local.findMessageByClientId(
                message.conversationId,
                message.clientMsgId)?.state != "ACTIVE") {
            return null
        }
        local.mediaCache().getOrNull(cacheName)?.let { return it }
        val variant = if (thumbnail) "thumb" else "full"
        httpClient.newCall(
            get("/api/v1/media/$mediaId?variant=$variant", accessToken))
            .execute().use { response ->
                if (response.code == 404 || response.code == 410) return null
                if (!response.isSuccessful) {
                    throw ConversationApiException(response.code, response.body?.string().orEmpty())
                }
                val bytes = response.body?.bytes() ?: return null
                if (!local.putMessageMediaIfActive(
                        message.conversationId,
                        message.clientMsgId,
                        cacheName,
                        bytes)) {
                    return null
                }
                return bytes
            }
    }

    fun deleteMessageMedia(local: LocalDatabase, mediaId: String?) {
        local.deleteMessageMediaCache(mediaId)
    }

    private inline fun <reified T> requestJson(request: Request): T =
        json.decodeFromString(execute(request))

    private inline fun <reified T> requestJsonOrNull(request: Request): T? {
        return try {
            requestJson(request)
        } catch (exception: ConversationApiException) {
            if (exception.statusCode == 404) null else throw exception
        }
    }

    private fun get(path: String, accessToken: String): Request = Request.Builder()
        .url(url(path))
        .header("Authorization", "Bearer $accessToken")
        .get()
        .build()

    private fun post(path: String, accessToken: String): Request =
        requestWithoutBody(path, accessToken, "POST")

    private inline fun <reified T> post(
        path: String,
        accessToken: String,
        body: T,
    ): Request {
        val builder = Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer $accessToken")
        builder.post(json.encodeToString(body).toRequestBody(jsonMediaType))
        builder.header("Content-Type", jsonMediaType.toString())
        return builder.build()
    }

    private fun requestWithoutBody(
        path: String,
        accessToken: String,
        method: String,
    ): Request {
        val builder = Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer $accessToken")
        if (method == "POST") {
            builder.post(ByteArray(0).toRequestBody())
        } else {
            builder.method(method, null)
        }
        return builder.build()
    }

    private fun execute(request: Request): String {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) return body
            throw ConversationApiException(response.code, body)
        }
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path
}

class ConversationApiException(
    val statusCode: Int,
    val responseBody: String,
) : IOException("Conversation request failed with HTTP $statusCode")

class SyncGapException(
    val localSyncSeq: Long,
    val receivedSyncSeq: Long,
) : IOException(
    "Received sync sequence $receivedSyncSeq after local sequence $localSyncSeq")

class RealtimeCommandException(
    val code: String,
    override val message: String,
) : IOException("$code: $message")
