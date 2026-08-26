package com.jitong.im.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jitong.im.android.group.GroupRepository
import com.jitong.im.android.group.GroupInviteResolveResponse
import com.jitong.im.android.group.GroupInviteResponse
import com.jitong.im.android.group.GroupJoinRequestSummary
import com.jitong.im.android.group.GroupMemberSummary
import com.jitong.im.android.group.GroupGovernancePolicy
import com.jitong.im.android.group.GroupSearchResult
import com.jitong.im.android.group.GroupSummary
import com.jitong.im.android.message.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal data class GroupUiState(
    val loading: Boolean = false,
    val name: String = "",
    val description: String = "",
    val visibility: String = "PUBLIC",
    val avatar: ByteArray? = null,
    val groups: List<GroupSummary> = emptyList(),
    val messageListGroups: List<GroupSummary> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<GroupSearchResult> = emptyList(),
    val inviteToken: String = "",
    val autoResolveInvite: Boolean = false,
    val invite: GroupInviteResolveResponse? = null,
    val createdInvite: GroupInviteResponse? = null,
    val joinRequests: List<GroupJoinRequestSummary> = emptyList(),
    val joinRequestGroupId: UUID? = null,
    val members: List<GroupMemberSummary> = emptyList(),
    val memberGroupId: UUID? = null,
    val directInviteAccountNo: String = "",
    val message: String? = null,
)

internal class GroupViewModel(
    private val repository: GroupRepository,
    private val clearGroupData: suspend (UUID) -> Unit = {},
    private val messageRepository: MessageRepository? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(GroupUiState())
    val state: StateFlow<GroupUiState> = _state.asStateFlow()

    init {
        messageRepository?.let { messages ->
            viewModelScope.launch {
                messages.conversationChanges.collect {
                    runCatching { refreshData() }
                }
            }
        }
    }

    fun setName(value: String) {
        _state.value = _state.value.copy(name = value, message = null)
    }

    fun setDescription(value: String) {
        _state.value = _state.value.copy(description = value, message = null)
    }

    fun setVisibility(value: String) {
        _state.value = _state.value.copy(visibility = value, message = null)
    }

    fun setAvatar(value: ByteArray?) {
        _state.value = _state.value.copy(avatar = value, message = null)
    }

    fun setSearchQuery(value: String) {
        _state.value = _state.value.copy(searchQuery = value, message = null)
    }

    fun setInviteToken(value: String) {
        _state.value = _state.value.copy(
            inviteToken = value.trim(),
            autoResolveInvite = false,
            invite = null,
            createdInvite = null,
            message = null,
        )
    }

    fun setDirectInviteAccountNo(value: String) {
        _state.value = _state.value.copy(
            directInviteAccountNo = value.filter(Char::isDigit).take(11),
            message = null,
        )
    }

    fun directInvite(group: GroupSummary) {
        val accountNo = _state.value.directInviteAccountNo
        if (!accountNo.matches(Regex("[1-9][0-9]{10}"))) {
            _state.value = _state.value.copy(message = "请输入完整 11 位账号")
            return
        }
        launchRequest {
            repository.addMember(group.conversationId, accountNo)
            _state.value = _state.value.copy(
                directInviteAccountNo = "",
                message = "已邀请成员加入群聊",
            )
            refreshMembersIfLoaded(group.conversationId)
            refreshData()
        }
    }

    fun promoteMember(group: GroupSummary, userId: UUID) {
        if (!GroupGovernancePolicy.canChangeRole(group.role, "MEMBER")) {
            _state.value = _state.value.copy(message = "只有群主可以调整成员角色")
            return
        }
        launchRequest {
            repository.changeRole(group.conversationId, userId, "ADMIN")
            refreshMembers(group.conversationId)
            refreshData()
        }
    }

    fun demoteMember(group: GroupSummary, userId: UUID) {
        if (!GroupGovernancePolicy.canChangeRole(group.role, "ADMIN")) {
            _state.value = _state.value.copy(message = "只有群主可以调整管理员角色")
            return
        }
        launchRequest {
            repository.changeRole(group.conversationId, userId, "MEMBER")
            refreshMembers(group.conversationId)
            refreshData()
        }
    }

    fun transferOwner(group: GroupSummary, userId: UUID) {
        if (!GroupGovernancePolicy.canTransferOwner(group.role, "ADMIN")) {
            _state.value = _state.value.copy(message = "只有群主可以转让群主")
            return
        }
        launchRequest {
            repository.transferOwner(group.conversationId, userId)
            refreshMembers(group.conversationId)
            refreshData()
        }
    }

    fun updateProfile(group: GroupSummary) {
        if (!GroupGovernancePolicy.canEditProfile(group.role)) {
            _state.value = _state.value.copy(message = "只有群主或管理员可以修改群资料")
            return
        }
        val current = _state.value
        launchRequest {
            repository.updateProfile(
                group.conversationId,
                current.name.trim(),
                current.description.trim(),
                current.visibility,
            )
            refreshMembersIfLoaded(group.conversationId)
            refreshData()
        }
    }

    fun leave(group: GroupSummary) {
        launchRequest {
            repository.leave(group.conversationId)
            clearGroupData(group.conversationId)
            refreshData()
        }
    }

    fun hideConversationFromMessageList(
        conversationId: UUID,
        hiddenAfterSequence: Long,
    ) {
        viewModelScope.launch {
            runCatching {
                messageRepository?.hideConversationFromMessageList(
                    conversationId,
                    hiddenAfterSequence,
                )
                refreshData()
            }.onFailure {
                _state.value = _state.value.copy(message = "删除会话失败，请稍后重试")
            }
        }
    }

    fun dissolve(group: GroupSummary) {
        if (group.role != "OWNER") {
            _state.value = _state.value.copy(message = "只有群主可以解散群聊")
            return
        }
        launchRequest {
            repository.dissolve(group.conversationId)
            clearGroupData(group.conversationId)
            _state.value = _state.value.copy(
                memberGroupId = null,
                members = emptyList(),
            )
            refreshData()
        }
    }

    fun loadMembers(group: GroupSummary) {
        if (!GroupGovernancePolicy.canEditProfile(group.role)) {
            _state.value = _state.value.copy(message = "只有群主或管理员可以打开群管理")
            return
        }
        _state.value = _state.value.copy(
            name = group.name,
            description = group.description,
            visibility = group.visibility,
            message = null,
        )
        launchRequest {
            refreshMembers(group.conversationId)
        }
    }

    fun openInviteToken(value: String) {
        _state.value = _state.value.copy(
            inviteToken = value.trim(),
            autoResolveInvite = true,
            invite = null,
            createdInvite = null,
            message = null,
        )
    }

    fun resolveInvite() {
        val token = _state.value.inviteToken
        if (token.isBlank()) {
            _state.value = _state.value.copy(message = "请输入邀请链接中的令牌")
            return
        }
        _state.value = _state.value.copy(autoResolveInvite = false)
        launchRequest {
            _state.value = _state.value.copy(invite = repository.resolveInvite(token))
        }
    }

    fun requestToJoin() {
        val invite = _state.value.invite ?: return
        launchRequest {
            repository.createJoinRequest(invite.conversationId, _state.value.inviteToken)
            _state.value = _state.value.copy(message = "申请已提交，等待群主或管理员审批")
        }
    }

    fun createInvite(group: GroupSummary) {
        launchRequest {
            _state.value = _state.value.copy(
                createdInvite = repository.createInvite(group.conversationId),
            )
        }
    }

    fun loadJoinRequests(group: GroupSummary) {
        if (!GroupGovernancePolicy.canApproveJoinRequests(group.role)) {
            _state.value = _state.value.copy(message = "只有群主或管理员可以审批入群")
            return
        }
        launchRequest {
            _state.value = _state.value.copy(
                joinRequestGroupId = group.conversationId,
                joinRequests = repository.listJoinRequests(group.conversationId),
            )
        }
    }

    fun approveJoinRequest(request: GroupJoinRequestSummary) {
        launchRequest {
            repository.approveJoinRequest(request.conversationId, request.requestId)
            refreshJoinRequests(request.conversationId)
            refreshMembersIfLoaded(request.conversationId)
            refreshData()
        }
    }

    fun rejectJoinRequest(request: GroupJoinRequestSummary) {
        launchRequest {
            repository.rejectJoinRequest(request.conversationId, request.requestId)
            refreshJoinRequests(request.conversationId)
        }
    }

    fun removeMember(group: GroupSummary, member: GroupMemberSummary) {
        if (!GroupGovernancePolicy.canRemoveMember(group.role, member.role)) {
            _state.value = _state.value.copy(message = "当前角色不能移除该成员")
            return
        }
        launchRequest {
            repository.removeMember(group.conversationId, member.userId)
            refreshMembers(group.conversationId)
            refreshData()
        }
    }

    private suspend fun refreshJoinRequests(conversationId: UUID) {
        _state.value = _state.value.copy(
            joinRequestGroupId = conversationId,
            joinRequests = repository.listJoinRequests(conversationId),
        )
    }

    private suspend fun refreshMembers(conversationId: UUID) {
        _state.value = _state.value.copy(
            memberGroupId = conversationId,
            members = repository.listMembers(conversationId),
        )
    }

    private suspend fun refreshMembersIfLoaded(conversationId: UUID) {
        if (_state.value.memberGroupId == conversationId) {
            refreshMembers(conversationId)
        }
    }

    fun create() {
        val current = _state.value
        if (current.name.trim().isEmpty()) {
            _state.value = current.copy(message = "请输入群名称")
            return
        }
        launchRequest {
            repository.create(
                current.name.trim(),
                current.description.trim(),
                current.visibility,
                current.avatar,
            )
            _state.value = _state.value.copy(
                name = "",
                description = "",
                avatar = null,
            )
            refreshData()
        }
    }

    fun search() {
        val query = _state.value.searchQuery.trim()
        if (query.isEmpty()) {
            _state.value = _state.value.copy(message = "请输入群号或群名称")
            return
        }
        launchRequest {
            _state.value = _state.value.copy(searchResults = repository.search(query))
        }
    }

    fun refresh() {
        launchRequest { refreshData() }
    }

    private suspend fun refreshData() {
        val groups = repository.list()
        _state.value = _state.value.copy(
            groups = groups,
            messageListGroups = messageRepository?.filterGroupsForMessageList(groups) ?: groups,
        )
    }

    private fun launchRequest(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            runCatching { block() }
                .onFailure {
                    _state.value = _state.value.copy(message = "群操作失败，请稍后重试")
                }
            _state.value = _state.value.copy(loading = false)
        }
    }

    class Factory(
        private val repository: GroupRepository,
        private val clearGroupData: suspend (UUID) -> Unit = {},
        private val messageRepository: MessageRepository? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GroupViewModel(repository, clearGroupData, messageRepository) as T
    }
}
