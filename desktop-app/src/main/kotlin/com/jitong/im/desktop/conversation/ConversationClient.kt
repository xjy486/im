package com.jitong.im.desktop.conversation

import com.jitong.im.desktop.local.LocalConversation
import com.jitong.im.desktop.local.LocalDatabase
import com.jitong.im.desktop.local.LocalMessage
import com.jitong.im.desktop.local.LocalReadState
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

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
)

@Serializable
data class DesktopContactSearchResult(
    val version: Int,
    val accountNo: String,
    val displayName: String,
    val avatarUrl: String?,
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
    val text: String,
    val serverAcceptedAt: String,
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
    val text: String,
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
    val serverAcceptedAt: String? = null,
    val syncSeq: Long? = null,
    val userId: String? = null,
    val readSeq: Long? = null,
    val highWatermark: Long? = null,
    val code: String? = null,
    val message: String? = null,
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
                body = DesktopSendMessageRequest(clientMsgId, text)))

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
                status = conversation.status,
                relationship = conversation.relationship,
                blockedByMe = conversation.blockedByMe,
                readSeq = conversation.readSeq,
                peerReadSeq = conversation.peerReadSeq,
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
                    status = it.status,
                    relationship = it.relationship,
                    blockedByMe = it.blockedByMe,
                    readSeq = it.readSeq,
                    peerReadSeq = it.peerReadSeq,
                    updatedAt = System.currentTimeMillis())
            })
    }

    fun applyMessage(
        local: LocalDatabase,
        message: DesktopMessage,
        currentUserId: String,
    ) {
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
                text = message.text,
                serverAcceptedAt = message.serverAcceptedAt,
                createdAt = System.currentTimeMillis()))
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
            text = text.orEmpty(),
            serverAcceptedAt = serverAcceptedAt.orEmpty())
    }

    private inline fun <reified T> requestJson(request: Request): T =
        json.decodeFromString(execute(request))

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
        builder.method(method, null)
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
