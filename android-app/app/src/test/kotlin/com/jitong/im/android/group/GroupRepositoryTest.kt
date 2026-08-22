package com.jitong.im.android.group

import com.jitong.im.android.media.AvatarUploadResponse
import kotlinx.coroutines.test.runTest
import retrofit2.Response
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupRepositoryTest {

    @Test
    fun creates_a_group_and_uploads_the_optional_group_avatar() = runTest {
        val conversationId = UUID.randomUUID()
        var uploadedConversationId: UUID? = null
        val avatarUploader = object : GroupAvatarUploader {
            override suspend fun replaceGroupAvatar(
                conversationId: UUID,
                source: ByteArray,
            ): AvatarUploadResponse {
                uploadedConversationId = conversationId
                return AvatarUploadResponse(
                    version = 1,
                    mediaId = UUID.randomUUID(),
                    purpose = "AVATAR",
                    state = "BOUND",
                    contentType = "image/webp",
                    width = 512,
                    height = 512,
                    byteSize = source.size.toLong(),
                    avatarVersion = 1,
                    thumbnailUrl = "/avatar",
                )
            }
        }
        val expected = GroupCreateResponse(
            version = 1,
            conversationId = conversationId,
            groupNo = "12345678903",
            name = "Test group",
            description = "Description",
            visibility = "PUBLIC",
            ownerUserId = UUID.randomUUID(),
            role = "OWNER",
            avatarUrl = null,
            avatarVersion = 0,
            memberCount = 1,
        )
        val repository = GroupRepository(
            api = object : GroupApi {
                override suspend fun create(request: CreateGroupRequest) =
                    Response.success(expected)

                override suspend fun list() = error("not used")

                override suspend fun leave(conversationId: UUID) =
                    error("not used")

                override suspend fun search(query: String) = error("not used")

                override suspend fun createInvite(
                    conversationId: UUID,
                    request: GroupInviteCreateRequest?,
                ) = error("not used")

                override suspend fun resolveInvite(token: String) = error("not used")

                override suspend fun revokeInvite(conversationId: UUID, inviteId: UUID) =
                    error("not used")

                override suspend fun createJoinRequest(
                    conversationId: UUID,
                    request: GroupJoinRequestCreateRequest?,
                ) = error("not used")

                override suspend fun listJoinRequests(conversationId: UUID) = error("not used")

                override suspend fun approveJoinRequest(conversationId: UUID, requestId: UUID) =
                    error("not used")

                override suspend fun rejectJoinRequest(conversationId: UUID, requestId: UUID) =
                    error("not used")

                override suspend fun cancelJoinRequest(conversationId: UUID, requestId: UUID) =
                    error("not used")

                override suspend fun removeMember(conversationId: UUID, userId: UUID) =
                    error("not used")

                override suspend fun addMember(
                    conversationId: UUID,
                    request: GroupMemberAddRequest,
                ) = error("not used")

                override suspend fun changeRole(
                    conversationId: UUID,
                    userId: UUID,
                    request: GroupRoleChangeRequest,
                ) = error("not used")

                override suspend fun transferOwner(
                    conversationId: UUID,
                    request: GroupOwnerTransferRequest,
                ) = error("not used")

                override suspend fun updateProfile(
                    conversationId: UUID,
                    request: GroupProfileUpdateRequest,
                ) = error("not used")

                override suspend fun banUser(
                    conversationId: UUID,
                    userId: UUID,
                    request: GroupBanRequest?,
                ) = error("not used")

                override suspend fun unbanUser(conversationId: UUID, userId: UUID) =
                    error("not used")
            },
            avatarUploader = avatarUploader,
        )

        val actual = repository.create(
            name = "Test group",
            description = "Description",
            visibility = "PUBLIC",
            avatar = byteArrayOf(1, 2, 3),
        )

        assertEquals(expected, actual)
        assertEquals(conversationId, uploadedConversationId)
    }
}
