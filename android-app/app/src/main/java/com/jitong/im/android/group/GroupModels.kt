package com.jitong.im.android.group

import java.util.UUID

internal data class CreateGroupRequest(
    val name: String,
    val description: String,
    val visibility: String,
)

internal data class GroupCreateResponse(
    val version: Int,
    val conversationId: UUID,
    val groupNo: String,
    val name: String,
    val description: String,
    val visibility: String,
    val ownerUserId: UUID,
    val role: String,
    val avatarUrl: String?,
    val avatarVersion: Long,
    val memberCount: Int,
    val aiEnabled: Boolean = false,
    val aiPolicyVersion: Long = 1,
)

internal data class GroupSummary(
    val version: Int,
    val conversationId: UUID,
    val groupNo: String,
    val name: String,
    val description: String,
    val visibility: String,
    val role: String,
    val avatarUrl: String?,
    val avatarVersion: Long,
    val memberCount: Int,
    val aiEnabled: Boolean = false,
    val aiPolicyVersion: Long = 1,
)

internal data class GroupSearchResult(
    val name: String,
    val description: String,
    val avatarUrl: String?,
    val memberCount: Int,
)

internal data class GroupSearchPage(
    val version: Int,
    val groups: List<GroupSearchResult>,
)

internal data class GroupInviteCreateRequest(
    val maxUses: Int? = null,
    val expiresInSeconds: Long? = null,
)

internal data class GroupInviteResponse(
    val version: Int,
    val inviteId: UUID,
    val conversationId: UUID,
    val maxUses: Int,
    val useCount: Int,
    val expiresAt: String,
    val deepLink: String,
    val qrPayload: String,
)

internal data class GroupInviteResolveResponse(
    val version: Int,
    val conversationId: UUID,
    val groupNo: String,
    val name: String,
    val description: String,
    val visibility: String,
    val avatarUrl: String?,
    val avatarVersion: Long,
    val memberCount: Int,
    val expiresAt: String,
)

internal data class GroupJoinRequestCreateRequest(
    val inviteToken: String? = null,
)

internal data class GroupJoinRequestResponse(
    val version: Int,
    val requestId: UUID,
    val conversationId: UUID,
    val userId: UUID,
    val status: String,
    val inviteId: UUID?,
    val createdAt: String,
    val resolvedAt: String?,
)

internal data class GroupJoinRequestSummary(
    val version: Int,
    val requestId: UUID,
    val conversationId: UUID,
    val userId: UUID,
    val accountNo: String,
    val displayName: String,
    val status: String,
    val inviteId: UUID?,
    val createdAt: String,
    val resolvedAt: String?,
)

internal data class GroupMemberSummary(
    val version: Int,
    val userId: UUID,
    val accountNo: String,
    val displayName: String,
    val role: String,
    val avatarUrl: String?,
    val avatarVersion: Long,
    val avatarFallback: String,
)

internal data class GroupBanRequest(
    val reason: String? = null,
)

internal data class GroupMemberAddRequest(
    val accountNo: String,
)

internal data class GroupMemberAddResponse(
    val version: Int,
    val conversationId: UUID,
    val userId: UUID,
    val role: String,
    val memberCount: Int,
)

internal data class GroupRoleChangeRequest(
    val role: String,
)

internal data class GroupRoleChangeResponse(
    val version: Int,
    val conversationId: UUID,
    val userId: UUID,
    val role: String,
)

internal data class GroupOwnerTransferRequest(
    val userId: UUID,
)

internal data class GroupOwnerTransferResponse(
    val version: Int,
    val conversationId: UUID,
    val previousOwnerUserId: UUID,
    val ownerUserId: UUID,
)

internal data class GroupProfileUpdateRequest(
    val name: String,
    val description: String,
    val visibility: String,
)
