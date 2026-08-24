package com.jitong.im.desktop.conversation

import com.jitong.im.desktop.local.LocalConversation
import com.jitong.im.desktop.local.LocalDatabase
import com.jitong.im.desktop.local.LocalMessage
import com.jitong.im.desktop.local.LocalReadState
import com.jitong.im.desktop.local.LocalGroupProfile
import com.jitong.im.desktop.local.LocalAiArtifact
import com.jitong.im.desktop.local.LocalAiActionItem
import com.jitong.im.desktop.local.LocalAiJob
import com.jitong.im.desktop.media.ImageNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    val peerAccountNo: String?,
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
data class DesktopGroupSummary(
    val version: Int = 1,
    val conversationId: String,
    val groupNo: String,
    val name: String,
    val description: String,
    val visibility: String,
    val role: String,
    val avatarUrl: String? = null,
    val avatarVersion: Long = 0,
    val memberCount: Int = 0,
    val aiEnabled: Boolean = false,
    val aiPolicyVersion: Long = 1,
)

@Serializable
data class DesktopGroupAiPolicy(
    val version: Int = 1,
    val conversationId: String,
    val enabled: Boolean,
    val policyVersion: Long,
)

@Serializable
data class DesktopGroupAiPolicyUpdate(val enabled: Boolean)

@Serializable
data class DesktopGroupSearchResult(
    val name: String,
    val avatarUrl: String? = null,
    val description: String,
    val memberCount: Int,
)

@Serializable
data class DesktopGroupSearchPage(
    val version: Int = 1,
    val groups: List<DesktopGroupSearchResult>,
)

@Serializable
data class DesktopGroupJoinRequestByGroupNo(
    val groupNo: String,
    val inviteToken: String? = null,
)

@Serializable
data class DesktopGroupJoinRequest(
    val version: Int = 1,
    val requestId: String,
    val conversationId: String,
    val userId: String,
    val status: String,
    val inviteId: String? = null,
    val createdAt: String,
    val resolvedAt: String? = null,
)

@Serializable
data class DesktopMyGroupJoinRequest(
    val version: Int = 1,
    val requestId: String,
    val conversationId: String,
    val groupNo: String,
    val groupName: String,
    val status: String,
    val createdAt: String,
    val resolvedAt: String? = null,
)

@Serializable
data class DesktopGroupMember(
    val version: Int = 1,
    val userId: String,
    val accountNo: String,
    val displayName: String,
    val role: String,
    val avatarUrl: String? = null,
    val avatarVersion: Long = 0,
    val avatarFallback: String = "?",
)

@Serializable
data class DesktopGroupMemberAddRequest(val accountNo: String)

@Serializable
data class DesktopGroupRoleChangeRequest(val role: String)

@Serializable
data class DesktopGroupOwnerTransferRequest(val userId: String)

@Serializable
data class DesktopGroupProfileUpdateRequest(
    val name: String,
    val description: String,
    val visibility: String,
)

@Serializable
data class DesktopGroupInvite(
    val version: Int = 1,
    val inviteId: String,
    val conversationId: String,
    val maxUses: Int,
    val useCount: Int,
    val expiresAt: String,
    val deepLink: String,
    val qrPayload: String,
)

@Serializable
data class DesktopGroupBanRequest(val reason: String? = null)

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
data class DesktopAiArtifact(
    val version: Int,
    val artifactId: String,
    val jobId: String,
    val conversationId: String,
    val artifactType: String,
    val content: JsonElement,
    val createdAt: String,
    val expiresAt: String,
)

@Serializable
data class DesktopAiActionItem(
    val version: Int,
    val actionItemId: String,
    val sourceJobId: String?,
    val ownerUserId: String,
    val conversationId: String,
    val assigneeUserId: String?,
    val title: String,
    val details: String,
    val dueAt: String?,
    val priority: String,
    val confidence: Double,
    val sourceMessageIds: List<String>,
    val status: String,
    val createdAt: String,
    val completedAt: String?,
)

@Serializable
data class DesktopAiConsent(
    val version: Int,
    val conversationId: String,
    val userId: String,
    val enabled: Boolean,
    val enabledForBoth: Boolean,
    val policyVersion: Long,
)

@Serializable
data class DesktopAiJob(
    val version: Int,
    val jobId: String,
    val conversationId: String,
    val kind: String,
    val status: String,
    val errorCode: String?,
    val result: JsonElement? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class DesktopAiConsentUpdate(val enabled: Boolean)

@Serializable
data class DesktopAiRequest(val requestId: String = UUID.randomUUID().toString())

@Serializable
data class DesktopAiSummaryRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val afterSeq: Long? = null,
    val untilSeq: Long? = null,
)

@Serializable
data class DesktopAiExtractionRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val messageIds: List<String>,
)

@Serializable
data class DesktopAiActionItemUpdate(val status: String)

data class DesktopAiDraft(val text: String, val tone: String)

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
    val senderDisplayName: String = "",
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
    val senderDisplayName: String? = null,
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

    fun listGroups(accessToken: String): List<DesktopGroupSummary> =
        requestJson(get("/api/v1/groups", accessToken))

    fun searchGroups(
        accessToken: String,
        query: String,
    ): DesktopGroupSearchPage =
        requestJson(
            get(
                "/api/v1/groups/search?query=${java.net.URLEncoder.encode(
                    query,
                    Charsets.UTF_8)}",
                accessToken))

    fun requestToJoinGroup(
        accessToken: String,
        groupNo: String,
        inviteToken: String? = null,
    ): DesktopGroupJoinRequest =
        requestJson(
            post(
                "/api/v1/groups/join-requests/by-group-no",
                accessToken,
                DesktopGroupJoinRequestByGroupNo(groupNo, inviteToken)))

    fun listMyGroupJoinRequests(accessToken: String): List<DesktopMyGroupJoinRequest> =
        requestJson(get("/api/v1/groups/join-requests/mine", accessToken))

    fun listGroupMembers(
        accessToken: String,
        conversationId: String,
    ): List<DesktopGroupMember> =
        requestJson(get("/api/v1/groups/$conversationId/members", accessToken))

    fun addGroupMember(
        accessToken: String,
        conversationId: String,
        accountNo: String,
    ) {
        execute(
            post(
                "/api/v1/groups/$conversationId/members",
                accessToken,
                DesktopGroupMemberAddRequest(accountNo)))
    }

    fun removeGroupMember(
        accessToken: String,
        conversationId: String,
        userId: String,
    ) {
        execute(
            requestWithoutBody(
                "/api/v1/groups/$conversationId/members/$userId",
                accessToken,
                "DELETE"))
    }

    fun changeGroupRole(
        accessToken: String,
        conversationId: String,
        userId: String,
        role: String,
    ) {
        execute(
            put(
                "/api/v1/groups/$conversationId/members/$userId/role",
                accessToken,
                DesktopGroupRoleChangeRequest(role)))
    }

    fun transferGroupOwner(
        accessToken: String,
        conversationId: String,
        userId: String,
    ) {
        execute(
            post(
                "/api/v1/groups/$conversationId/owner-transfer",
                accessToken,
                DesktopGroupOwnerTransferRequest(userId)))
    }

    fun updateGroupProfile(
        accessToken: String,
        conversationId: String,
        name: String,
        description: String,
        visibility: String,
    ): DesktopGroupSummary =
        requestJson(
            put(
                "/api/v1/groups/$conversationId/profile",
                accessToken,
                DesktopGroupProfileUpdateRequest(name, description, visibility)))

    fun leaveGroup(accessToken: String, conversationId: String) {
        execute(
            requestWithoutBody(
                "/api/v1/groups/$conversationId/leave",
                accessToken,
                "POST"))
    }

    fun dissolveGroup(accessToken: String, conversationId: String) {
        execute(
            requestWithoutBody(
                "/api/v1/groups/$conversationId",
                accessToken,
                "DELETE"))
    }

    fun createGroupInvite(
        accessToken: String,
        conversationId: String,
    ): DesktopGroupInvite =
        requestJson(
            post(
                "/api/v1/groups/$conversationId/invites",
                accessToken,
                emptyMap<String, String>()))

    fun approveGroupJoinRequest(
        accessToken: String,
        conversationId: String,
        requestId: String,
    ) {
        execute(
            requestWithoutBody(
                "/api/v1/groups/$conversationId/join-requests/$requestId/approve",
                accessToken,
                "POST"))
    }

    fun rejectGroupJoinRequest(
        accessToken: String,
        conversationId: String,
        requestId: String,
    ) {
        execute(
            requestWithoutBody(
                "/api/v1/groups/$conversationId/join-requests/$requestId/reject",
                accessToken,
                "POST"))
    }

    fun banGroupMember(
        accessToken: String,
        conversationId: String,
        userId: String,
        reason: String? = null,
    ) {
        execute(
            post(
                "/api/v1/groups/$conversationId/bans/$userId",
                accessToken,
                DesktopGroupBanRequest(reason)))
    }

    fun unbanGroupMember(
        accessToken: String,
        conversationId: String,
        userId: String,
    ) {
        execute(
            requestWithoutBody(
                "/api/v1/groups/$conversationId/bans/$userId",
                accessToken,
                "DELETE"))
    }

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
        val groups = listGroups(accessToken)
        val aiJobs = listAiJobs(accessToken)
        val aiArtifacts = listAiArtifacts(accessToken)
        val aiActionItems = listAiActionItems(accessToken)
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
        groups.forEach { group ->
            applyGroup(local, group)
            restoreConversation(
                accessToken,
                local,
                group.conversationId,
                currentUserId)
            restoreReadStates(accessToken, local, group.conversationId)
        }
        restoreGroupProfiles(
            accessToken,
            local,
            (conversations.map { it.conversationId } + groups.map { it.conversationId })
                .distinct())
        aiJobs.forEach { applyAiJob(local, it) }
        aiArtifacts.forEach { local.upsertAiArtifact(it.toLocal()) }
        aiActionItems.forEach { local.upsertAiActionItem(it.toLocal()) }
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

    fun listAiArtifacts(accessToken: String): List<DesktopAiArtifact> =
        requestJson(get("/api/v1/ai/artifacts", accessToken))

    fun listAiJobs(accessToken: String): List<DesktopAiJob> =
        requestJson(get("/api/v1/ai/jobs", accessToken))

    fun listAiActionItems(accessToken: String): List<DesktopAiActionItem> =
        requestJson(get("/api/v1/ai/action-items", accessToken))

    fun groupAiPolicy(accessToken: String, conversationId: String): DesktopGroupAiPolicy =
        requestJson(get("/api/v1/groups/$conversationId/ai-policy", accessToken))

    fun updateGroupAiPolicy(
        accessToken: String,
        conversationId: String,
        enabled: Boolean,
    ): DesktopGroupAiPolicy = requestJson(
        patch(
            "/api/v1/groups/$conversationId/ai-policy",
            accessToken,
            DesktopGroupAiPolicyUpdate(enabled)))

    fun aiConsent(accessToken: String, conversationId: String): DesktopAiConsent =
        requestJson(get("/api/v1/conversations/$conversationId/ai/consent", accessToken))

    fun updateAiConsent(
        accessToken: String,
        conversationId: String,
        enabled: Boolean,
    ): DesktopAiConsent = requestJson(
        patch(
            "/api/v1/conversations/$conversationId/ai/consent",
            accessToken,
            DesktopAiConsentUpdate(enabled)))

    fun requestSummary(
        accessToken: String,
        conversationId: String,
        afterSeq: Long? = null,
        untilSeq: Long? = null,
        onJobUpdate: (DesktopAiJob) -> Unit = {},
    ): DesktopAiJob = awaitAiJob(
        accessToken,
        requestJson(
            post(
                "/api/v1/conversations/$conversationId/ai/summary",
                accessToken,
                DesktopAiSummaryRequest(afterSeq = afterSeq, untilSeq = untilSeq))),
        onJobUpdate)

    fun requestSmartReplies(
        accessToken: String,
        conversationId: String,
        onJobUpdate: (DesktopAiJob) -> Unit = {},
    ): List<DesktopAiDraft> {
        val completed = awaitAiJob(
            accessToken,
            requestJson(
                post(
                    "/api/v1/conversations/$conversationId/ai/smart-replies",
                    accessToken,
                    DesktopAiRequest())),
            onJobUpdate)
        return completed.result?.jsonObject?.get("replies")?.jsonArray?.map { value ->
            val draft = value.jsonObject
            DesktopAiDraft(
                draft.getValue("text").jsonPrimitive.content,
                draft.getValue("tone").jsonPrimitive.content)
        }.orEmpty()
    }

    fun extractInformation(
        accessToken: String,
        conversationId: String,
        messageIds: List<String>,
        onJobUpdate: (DesktopAiJob) -> Unit = {},
    ): DesktopAiJob {
        require(messageIds.isNotEmpty() && messageIds.size <= 200)
        return awaitAiJob(
            accessToken,
            requestJson(
                post(
                    "/api/v1/conversations/$conversationId/ai/extract",
                    accessToken,
                    DesktopAiExtractionRequest(messageIds = messageIds))),
            onJobUpdate)
    }

    fun deleteAiJob(accessToken: String, jobId: String) {
        execute(requestWithoutBody("/api/v1/ai/jobs/$jobId", accessToken, "DELETE"))
    }

    fun deleteAiArtifact(accessToken: String, artifactId: String) {
        execute(requestWithoutBody("/api/v1/ai/artifacts/$artifactId", accessToken, "DELETE"))
    }

    fun updateAiActionItem(
        accessToken: String,
        actionItemId: String,
        status: String,
    ): DesktopAiActionItem = requestJson(
        patch(
            "/api/v1/ai/action-items/$actionItemId",
            accessToken,
            DesktopAiActionItemUpdate(status)))

    fun deleteAiActionItem(accessToken: String, actionItemId: String) {
        execute(requestWithoutBody("/api/v1/ai/action-items/$actionItemId", accessToken, "DELETE"))
    }

    fun refreshAiData(accessToken: String, local: LocalDatabase) {
        val jobs = listAiJobs(accessToken)
        val artifacts = listAiArtifacts(accessToken)
        val actionItems = listAiActionItems(accessToken)
        local.clearAiJobs()
        local.clearAiArtifacts()
        local.clearAiActionItems()
        jobs.forEach { applyAiJob(local, it) }
        artifacts.forEach { local.upsertAiArtifact(it.toLocal()) }
        actionItems.forEach { local.upsertAiActionItem(it.toLocal()) }
    }

    fun applyAiSyncEvents(
        accessToken: String,
        local: LocalDatabase,
        events: List<DesktopSyncEvent>,
    ) {
        if (events.any { it.eventType.startsWith("AI_") }) refreshAiData(accessToken, local)
    }

    fun applyAiJob(local: LocalDatabase, job: DesktopAiJob) {
        local.upsertAiJob(job.toLocal())
    }

    private fun DesktopAiArtifact.toLocal() = LocalAiArtifact(
        artifactId = artifactId,
        jobId = jobId,
        conversationId = conversationId,
        artifactType = artifactType,
        contentJson = content.toString(),
        createdAt = createdAt,
        expiresAt = expiresAt,
    )

    private fun DesktopAiActionItem.toLocal() = LocalAiActionItem(
        actionItemId = actionItemId,
        sourceJobId = sourceJobId,
        ownerUserId = ownerUserId,
        conversationId = conversationId,
        assigneeUserId = assigneeUserId,
        title = title,
        details = details,
        dueAt = dueAt,
        priority = priority,
        confidence = confidence,
        sourceMessageIdsJson = json.encodeToString(sourceMessageIds),
        status = status,
        createdAt = createdAt,
        completedAt = completedAt,
    )

    private fun DesktopAiJob.toLocal() = LocalAiJob(
        jobId = jobId,
        conversationId = conversationId,
        kind = kind,
        status = status,
        errorCode = errorCode,
        createdAt = createdAt ?: "1970-01-01T00:00:00Z",
        expiresAt = expiresAt ?: "9999-12-31T23:59:59Z",
    )

    private fun awaitAiJob(
        accessToken: String,
        initial: DesktopAiJob,
        onJobUpdate: (DesktopAiJob) -> Unit,
    ): DesktopAiJob {
        var current = initial
        repeat(120) {
            onJobUpdate(current)
            if (current.status == "SUCCEEDED") return current
            if (current.status in setOf("FAILED", "CANCELLED", "EXPIRED")) {
                throw IllegalStateException(current.errorCode ?: "AI request failed")
            }
            Thread.sleep(250)
            current = requestJson(get("/api/v1/ai/jobs/${current.jobId}", accessToken))
        }
        throw IllegalStateException("AI request timed out")
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
                local.updateMessageSenderDisplayName(
                    profile.userId,
                    profile.displayName)
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
                local.updateGroupAvatar(
                    profile.conversationId,
                    profile.avatarUrl,
                    profile.avatarVersion)
                local.mediaCache().deleteMatching("group-avatar-$conversationId-v")
            }
        events
            .filter {
                (it.eventType == "GROUP_ACCESS_REVOKED"
                    || it.eventType == "GROUP_DISSOLVED")
                    && it.conversationId != null
            }
            .map { it.conversationId!! }
            .distinct()
            .forEach(local::clearGroupData)
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
            local.updateGroupAvatar(
                profile.conversationId,
                profile.avatarUrl,
                profile.avatarVersion)
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
                kind = "C2C",
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

    fun applyGroup(local: LocalDatabase, group: DesktopGroupSummary) {
        local.upsertConversation(
            LocalConversation(
                conversationId = group.conversationId,
                kind = "GROUP",
                peerUserId = "",
                peerAccountNo = group.groupNo,
                peerDisplayName = group.name,
                peerAvatarUrl = group.avatarUrl,
                peerAvatarVersion = group.avatarVersion,
                peerAvatarFallback = group.name.firstOrNull()?.toString() ?: "?",
                status = "ACTIVE",
                relationship = group.role,
                blockedByMe = false,
                readSeq = 0,
                peerReadSeq = 0,
                groupDescription = group.description,
                groupVisibility = group.visibility,
                groupMemberCount = group.memberCount,
                updatedAt = System.currentTimeMillis()))
        local.upsertGroupProfile(
            LocalGroupProfile(
                group.conversationId,
                group.avatarUrl,
                group.avatarVersion))
    }

    fun replaceGroups(local: LocalDatabase, groups: List<DesktopGroupSummary>) {
        local.replaceGroups(
            groups.map {
                LocalConversation(
                    conversationId = it.conversationId,
                    kind = "GROUP",
                    peerUserId = "",
                    peerAccountNo = it.groupNo,
                    peerDisplayName = it.name,
                    peerAvatarUrl = it.avatarUrl,
                    peerAvatarVersion = it.avatarVersion,
                    peerAvatarFallback = it.name.firstOrNull()?.toString() ?: "?",
                    status = "ACTIVE",
                    relationship = it.role,
                    blockedByMe = false,
                    readSeq = 0,
                    peerReadSeq = 0,
                    groupDescription = it.description,
                    groupVisibility = it.visibility,
                    groupMemberCount = it.memberCount,
                    updatedAt = System.currentTimeMillis())
            })
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
                senderDisplayName = message.senderDisplayName,
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
                senderDisplayName = message.senderDisplayName,
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
            local.updateMessageSenderDisplayName(
                userId,
                body.displayName.orEmpty())
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
            local.updateGroupAvatar(
                conversationId,
                body.avatarUrl,
                body.avatarVersion ?: 0)
            local.mediaCache().deleteMatching("group-avatar-$conversationId-v")
            syncSeq?.let(local::saveLastSyncSeq)
            return
        }
        if (envelope.operation == "membership.revoked") {
            val conversationId = body.conversationId ?: return
            local.clearGroupData(conversationId)
            syncSeq?.let(local::saveLastSyncSeq)
            return
        }
        if (envelope.operation == "membership.granted") {
            syncSeq?.let(local::saveLastSyncSeq)
            return
        }
        if (envelope.operation == "group.dissolved") {
            val conversationId = body.conversationId ?: return
            local.clearGroupData(conversationId)
            syncSeq?.let(local::saveLastSyncSeq)
            return
        }
        if (envelope.operation.startsWith("ai.")) {
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

    fun applyRealtimeAuthoritatively(
        accessToken: String,
        local: LocalDatabase,
        envelope: DesktopRealtimeEnvelope,
        currentUserId: String,
    ) {
        if (envelope.operation.startsWith("ai.")) {
            refreshAiData(accessToken, local)
        }
        applyRealtime(local, envelope, currentUserId)
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
            senderDisplayName = senderDisplayName.orEmpty(),
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

    private inline fun <reified T> put(
        path: String,
        accessToken: String,
        body: T,
    ): Request {
        val builder = Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer $accessToken")
        builder.put(json.encodeToString(body).toRequestBody(jsonMediaType))
        builder.header("Content-Type", jsonMediaType.toString())
        return builder.build()
    }

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

    private inline fun <reified T> patch(
        path: String,
        accessToken: String,
        body: T,
    ): Request {
        val builder = Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer $accessToken")
        builder.patch(json.encodeToString(body).toRequestBody(jsonMediaType))
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
