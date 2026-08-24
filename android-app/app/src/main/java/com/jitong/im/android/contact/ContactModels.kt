package com.jitong.im.android.contact

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
)
