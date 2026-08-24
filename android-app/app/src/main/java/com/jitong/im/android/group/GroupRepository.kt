package com.jitong.im.android.group

import retrofit2.Response
import java.io.IOException
import java.util.UUID

internal class GroupRepository(
    private val api: GroupApi,
    private val avatarUploader: GroupAvatarUploader,
) {
    suspend fun create(
        name: String,
        description: String,
        visibility: String,
        avatar: ByteArray?,
    ): GroupCreateResponse {
        val group = api.create(CreateGroupRequest(name, description, visibility))
            .bodyOrThrow("Group creation")
        if (avatar != null) {
            avatarUploader.replaceGroupAvatar(group.conversationId, avatar)
        }
        return group
    }

    suspend fun list(): List<GroupSummary> =
        api.list().bodyOrThrow("Group list")

    suspend fun leave(conversationId: UUID) {
        api.leave(conversationId).ensureSuccessful("Group leave")
    }

    suspend fun dissolve(conversationId: UUID) {
        api.dissolve(conversationId).ensureSuccessful("Group dissolution")
    }

    suspend fun search(query: String): List<GroupSearchResult> =
        api.search(query).bodyOrThrow("Group search").groups

    suspend fun createInvite(conversationId: UUID, maxUses: Int? = null): GroupInviteResponse =
        api.createInvite(
            conversationId,
            if (maxUses == null) null else GroupInviteCreateRequest(maxUses = maxUses),
        ).bodyOrThrow("Group invite creation")

    suspend fun resolveInvite(token: String): GroupInviteResolveResponse =
        api.resolveInvite(token).bodyOrThrow("Group invite resolution")

    suspend fun revokeInvite(conversationId: UUID, inviteId: UUID) {
        api.revokeInvite(conversationId, inviteId).ensureSuccessful("Group invite revocation")
    }

    suspend fun createJoinRequest(conversationId: UUID, inviteToken: String? = null): GroupJoinRequestResponse =
        api.createJoinRequest(
            conversationId,
            GroupJoinRequestCreateRequest(inviteToken),
        ).bodyOrThrow("Group join request")

    suspend fun listJoinRequests(conversationId: UUID): List<GroupJoinRequestSummary> =
        api.listJoinRequests(conversationId).bodyOrThrow("Group join request list")

    suspend fun listMembers(conversationId: UUID): List<GroupMemberSummary> =
        api.listMembers(conversationId).bodyOrThrow("Group member list")

    suspend fun approveJoinRequest(conversationId: UUID, requestId: UUID): GroupJoinRequestResponse =
        api.approveJoinRequest(conversationId, requestId).bodyOrThrow("Group join request approval")

    suspend fun rejectJoinRequest(conversationId: UUID, requestId: UUID): GroupJoinRequestResponse =
        api.rejectJoinRequest(conversationId, requestId).bodyOrThrow("Group join request rejection")

    suspend fun cancelJoinRequest(conversationId: UUID, requestId: UUID): GroupJoinRequestResponse =
        api.cancelJoinRequest(conversationId, requestId).bodyOrThrow("Group join request cancellation")

    suspend fun removeMember(conversationId: UUID, userId: UUID) {
        api.removeMember(conversationId, userId).ensureSuccessful("Group member removal")
    }

    suspend fun addMember(conversationId: UUID, accountNo: String): GroupMemberAddResponse =
        api.addMember(conversationId, GroupMemberAddRequest(accountNo))
            .bodyOrThrow("Direct group invitation")

    suspend fun changeRole(
        conversationId: UUID,
        userId: UUID,
        role: String,
    ): GroupRoleChangeResponse =
        api.changeRole(conversationId, userId, GroupRoleChangeRequest(role))
            .bodyOrThrow("Group role change")

    suspend fun transferOwner(
        conversationId: UUID,
        userId: UUID,
    ): GroupOwnerTransferResponse =
        api.transferOwner(conversationId, GroupOwnerTransferRequest(userId))
            .bodyOrThrow("Group owner transfer")

    suspend fun updateProfile(
        conversationId: UUID,
        name: String,
        description: String,
        visibility: String,
    ): GroupSummary =
        api.updateProfile(
            conversationId,
            GroupProfileUpdateRequest(name, description, visibility),
        ).bodyOrThrow("Group profile update")

    suspend fun banUser(conversationId: UUID, userId: UUID, reason: String? = null) {
        api.banUser(conversationId, userId, GroupBanRequest(reason)).ensureSuccessful("Group ban")
    }

    suspend fun unbanUser(conversationId: UUID, userId: UUID) {
        api.unbanUser(conversationId, userId).ensureSuccessful("Group unban")
    }

    private fun <T> Response<T>.bodyOrThrow(operation: String): T {
        if (isSuccessful && body() != null) return body()!!
        throw IOException("$operation failed with HTTP ${code()}")
    }

    private fun Response<*>.ensureSuccessful(operation: String) {
        if (!isSuccessful) throw IOException("$operation failed with HTTP ${code()}")
    }
}
