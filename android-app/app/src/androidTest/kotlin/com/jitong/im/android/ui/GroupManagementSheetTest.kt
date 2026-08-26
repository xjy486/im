package com.jitong.im.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jitong.im.android.group.CreateGroupRequest
import com.jitong.im.android.group.GroupApi
import com.jitong.im.android.group.GroupAvatarUploader
import com.jitong.im.android.group.GroupInviteCreateRequest
import com.jitong.im.android.group.GroupInviteResponse
import com.jitong.im.android.group.GroupInviteResolveResponse
import com.jitong.im.android.group.GroupJoinRequestByGroupNoRequest
import com.jitong.im.android.group.GroupJoinRequestCreateRequest
import com.jitong.im.android.group.GroupJoinRequestResponse
import com.jitong.im.android.group.GroupJoinRequestSummary
import com.jitong.im.android.group.GroupMemberInvitationRequest
import com.jitong.im.android.group.GroupMemberInvitationResponse
import com.jitong.im.android.group.GroupMemberInvitationSummary
import com.jitong.im.android.group.GroupMemberSummary
import com.jitong.im.android.group.GroupRepository
import com.jitong.im.android.group.GroupRoleChangeRequest
import com.jitong.im.android.group.GroupRoleChangeResponse
import com.jitong.im.android.group.GroupSearchPage
import com.jitong.im.android.group.GroupSummary
import com.jitong.im.android.group.GroupOwnerTransferRequest
import com.jitong.im.android.group.GroupOwnerTransferResponse
import com.jitong.im.android.group.GroupProfileUpdateRequest
import com.jitong.im.android.group.GroupBanRequest
import com.jitong.im.android.media.AvatarUploadResponse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GroupManagementSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deleting_group_profile_fields_to_empty_keeps_them_empty() {
        val viewModel = createViewModel()

        composeRule.setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                GroupManagementSheet(group(), state, viewModel, onDismiss = {})
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasSetTextAction() and hasText("大不列颠")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction() and hasText("大不列颠")).performTextReplacement("")
        composeRule.onNode(hasSetTextAction() and hasText("旧简介")).performTextReplacement("")

        composeRule.onAllNodes(hasSetTextAction() and hasText("")).assertCountEquals(2)
    }

    @Test
    fun inviting_member_opens_account_input_and_submits_account() {
        var invitedAccount: String? = null
        val viewModel = createViewModel(onInviteMember = { _, accountNo -> invitedAccount = accountNo })

        composeRule.setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                GroupManagementSheet(group(), state, viewModel, onDismiss = {})
            }
        }

        composeRule.onNode(hasText("邀请成员") and hasClickAction()).performClick()
        composeRule.onNode(hasSetTextAction() and hasText("", substring = false)).performTextInput("12345678901")
        composeRule.onNode(hasText("发送邀请")).performClick()
        composeRule.waitUntil(5_000) { invitedAccount == "12345678901" }

        assertEquals("12345678901", invitedAccount)
    }

    @Test
    fun generating_invite_link_opens_copy_and_share_actions() {
        var generatedFor: UUID? = null
        val viewModel = createViewModel(onCreateInvite = { conversationId -> generatedFor = conversationId })

        composeRule.setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                GroupManagementSheet(group(), state, viewModel, onDismiss = {})
            }
        }

        composeRule.onNode(hasText("生成邀请链接") and hasClickAction()).performClick()
        composeRule.waitUntil(5_000) {
            generatedFor == group().conversationId &&
                composeRule.onAllNodes(hasText("https://app.jitong.im/groups/invite?token=test-token"))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("复制链接")).assertExists()
        composeRule.onNode(hasText("分享链接")).assertExists()
    }

    private fun group(): GroupSummary = Fixtures.group()

    private fun createViewModel(
        onInviteMember: suspend (UUID, String) -> Unit = { _, _ -> },
        onCreateInvite: suspend (UUID) -> Unit = { _ -> },
    ): GroupViewModel = GroupViewModel(
        repository = GroupRepository(
            api = FakeGroupApi(onInviteMember, onCreateInvite),
            avatarUploader = object : GroupAvatarUploader {
                override suspend fun replaceGroupAvatar(
                    conversationId: UUID,
                    source: ByteArray,
                ): AvatarUploadResponse = error("not used")
            },
        ),
    )

    private class FakeGroupApi(
        private val onInviteMember: suspend (UUID, String) -> Unit,
        private val onCreateInvite: suspend (UUID) -> Unit,
    ) : GroupApi {
        override suspend fun create(request: CreateGroupRequest): Response<com.jitong.im.android.group.GroupCreateResponse> = error("not used")
        override suspend fun list(): Response<List<GroupSummary>> = Response.success(listOf(Fixtures.group()))
        override suspend fun leave(conversationId: UUID): Response<Unit> = error("not used")
        override suspend fun dissolve(conversationId: UUID): Response<Unit> = error("not used")
        override suspend fun search(query: String): Response<GroupSearchPage> = error("not used")
        override suspend fun createInvite(conversationId: UUID, request: GroupInviteCreateRequest?): Response<GroupInviteResponse> {
            onCreateInvite(conversationId)
            return Response.success(
                GroupInviteResponse(
                    version = 1,
                    inviteId = UUID.randomUUID(),
                    conversationId = conversationId,
                    maxUses = 100,
                    useCount = 0,
                    expiresAt = "2026-08-27T00:00:00Z",
                    deepLink = "https://app.jitong.im/groups/invite?token=test-token",
                    qrPayload = "https://app.jitong.im/groups/invite?token=test-token",
                ),
            )
        }
        override suspend fun resolveInvite(token: String): Response<GroupInviteResolveResponse> = error("not used")
        override suspend fun revokeInvite(conversationId: UUID, inviteId: UUID): Response<Unit> = error("not used")
        override suspend fun createJoinRequest(conversationId: UUID, request: GroupJoinRequestCreateRequest?): Response<GroupJoinRequestResponse> = error("not used")
        override suspend fun createJoinRequestByGroupNo(request: GroupJoinRequestByGroupNoRequest): Response<GroupJoinRequestResponse> = error("not used")
        override suspend fun listJoinRequests(conversationId: UUID): Response<List<GroupJoinRequestSummary>> = error("not used")
        override suspend fun listMembers(conversationId: UUID): Response<List<GroupMemberSummary>> = Response.success(emptyList())
        override suspend fun approveJoinRequest(conversationId: UUID, requestId: UUID): Response<GroupJoinRequestResponse> = error("not used")
        override suspend fun rejectJoinRequest(conversationId: UUID, requestId: UUID): Response<GroupJoinRequestResponse> = error("not used")
        override suspend fun cancelJoinRequest(conversationId: UUID, requestId: UUID): Response<GroupJoinRequestResponse> = error("not used")
        override suspend fun removeMember(conversationId: UUID, userId: UUID): Response<Unit> = error("not used")
        override suspend fun inviteMember(conversationId: UUID, request: GroupMemberInvitationRequest): Response<GroupMemberInvitationResponse> {
            onInviteMember(conversationId, request.accountNo)
            return Response.success(
                GroupMemberInvitationResponse(
                    version = 1,
                    invitationId = UUID.randomUUID(),
                    conversationId = conversationId,
                    inviterUserId = UUID.randomUUID(),
                    inviteeUserId = UUID.randomUUID(),
                    status = "PENDING",
                    createdAt = "2026-08-26T00:00:00Z",
                    resolvedAt = null,
                ),
            )
        }
        override suspend fun memberInvitations(): Response<List<GroupMemberInvitationSummary>> = Response.success(emptyList())
        override suspend fun acceptMemberInvitation(conversationId: UUID, invitationId: UUID): Response<GroupMemberInvitationResponse> = error("not used")
        override suspend fun rejectMemberInvitation(conversationId: UUID, invitationId: UUID): Response<GroupMemberInvitationResponse> = error("not used")
        override suspend fun changeRole(conversationId: UUID, userId: UUID, request: GroupRoleChangeRequest): Response<GroupRoleChangeResponse> = error("not used")
        override suspend fun transferOwner(conversationId: UUID, request: GroupOwnerTransferRequest): Response<GroupOwnerTransferResponse> = error("not used")
        override suspend fun updateProfile(conversationId: UUID, request: GroupProfileUpdateRequest): Response<GroupSummary> = error("not used")
        override suspend fun banUser(conversationId: UUID, userId: UUID, request: GroupBanRequest?): Response<Unit> = error("not used")
        override suspend fun unbanUser(conversationId: UUID, userId: UUID): Response<Unit> = error("not used")
    }

    private object Fixtures {
        private val GROUP_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        fun group(): GroupSummary = GroupSummary(
            version = 1,
            conversationId = GROUP_ID,
            groupNo = "12345678901",
            name = "大不列颠",
            description = "旧简介",
            visibility = "PRIVATE",
            role = "OWNER",
            avatarUrl = null,
            avatarVersion = 0,
            memberCount = 1,
        )
    }
}
