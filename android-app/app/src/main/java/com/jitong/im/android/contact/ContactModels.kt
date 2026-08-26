package com.jitong.im.android.contact

import com.google.gson.annotations.SerializedName
import java.util.UUID

internal data class CreateContactRequest(
    val accountNo: String,
    val verification: String = "",
)

internal data class ContactSearchResult(
    val version: Int,
    val accountNo: String,
    val displayName: String,
    val avatarUrl: String?,
    val avatarVersion: Long = 0,
    val avatarFallback: String = "?",
    val relationship: String,
    val pendingRequestId: String?,
)

internal data class ContactRequestResponse(
    val version: Int,
    val requestId: UUID,
    val requesterId: UUID,
    val recipientId: UUID,
    val status: String,
    val verification: String,
    val expiresAt: String,
    val conversationId: UUID?,
)

internal data class ContactRequestSummary(
    val version: Int,
    val requestId: UUID,
    val requesterId: UUID,
    val recipientId: UUID,
    val status: String,
    val verification: String,
    val expiresAt: String,
    val incoming: Boolean,
    val peerAccountNo: String?,
    val peerDisplayName: String,
)

internal data class ContactSummary(
    val version: Int,
    val userId: UUID,
    val accountNo: String,
    val displayName: String,
    val conversationId: UUID,
    val relationship: String,
    val avatarUrl: String? = null,
    val avatarVersion: Long = 0,
    val avatarFallback: String = "?",
)

internal data class ConversationSummary(
    val version: Int,
    val conversationId: UUID,
    val peerUserId: UUID,
    val peerAccountNo: String,
    val peerDisplayName: String,
    val status: String,
    val relationship: String,
    val blockedByMe: Boolean,
    val readSeq: Long = 0,
    val peerReadSeq: Long = 0,
    val avatarUrl: String? = null,
    val avatarVersion: Long = 0,
    val avatarFallback: String = "?",
    val unreadCount: Int = 0,
    val latestMessage: ConversationPreview? = null,
)

internal data class ConversationPreview(
    val conversationSeq: Long,
    val type: String,
    val text: String?,
    val state: String,
    @SerializedName("serverAcceptedAt")
    val serverAcceptedAt: String,
    val systemEventType: String? = null,
) {
    val sortTimestamp: Long
        get() = runCatching {
            java.time.Instant.parse(serverAcceptedAt).toEpochMilli()
        }.getOrDefault(Long.MIN_VALUE)
}

internal fun ConversationPreview.displayText(): String = when {
    type == "SYSTEM" && systemEventType == "CONTACT_ESTABLISHED" -> "你们已经成功加上好友了"
    state == "RECALLED" -> "消息已撤回"
    state == "MODERATED" -> "消息已被移除"
    type == "IMAGE" -> "图片"
    type == "TEXT" && !text.isNullOrBlank() -> text
    else -> "消息"
}

internal fun ConversationSummary.messageListPreview(): String =
    latestMessage?.displayText()
        ?: if (status == "READ_ONLY") "历史消息，只读" else "暂无消息"

internal fun List<ConversationSummary>.sortedForMessageList(): List<ConversationSummary> =
    sortedWith(
        compareByDescending<ConversationSummary> { it.latestMessage?.sortTimestamp ?: Long.MIN_VALUE }
            .thenByDescending { it.latestMessage?.conversationSeq ?: Long.MIN_VALUE }
            .thenBy { it.peerDisplayName.lowercase() }
            .thenBy { it.conversationId.toString() },
    )
