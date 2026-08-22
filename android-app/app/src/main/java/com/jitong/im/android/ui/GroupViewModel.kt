package com.jitong.im.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jitong.im.android.group.GroupRepository
import com.jitong.im.android.group.GroupInviteResolveResponse
import com.jitong.im.android.group.GroupInviteResponse
import com.jitong.im.android.group.GroupJoinRequestSummary
import com.jitong.im.android.group.GroupSearchResult
import com.jitong.im.android.group.GroupSummary
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
    val searchQuery: String = "",
    val searchResults: List<GroupSearchResult> = emptyList(),
    val inviteToken: String = "",
    val autoResolveInvite: Boolean = false,
    val invite: GroupInviteResolveResponse? = null,
    val createdInvite: GroupInviteResponse? = null,
    val joinRequests: List<GroupJoinRequestSummary> = emptyList(),
    val joinRequestGroupId: UUID? = null,
    val directInviteAccountNo: String = "",
    val message: String? = null,
)

internal class GroupViewModel(
    private val repository: GroupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(GroupUiState())
    val state: StateFlow<GroupUiState> = _state.asStateFlow()

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
            _state.value = _state.value.copy(directInviteAccountNo = "")
            refreshData()
        }
    }

    fun promoteMember(group: GroupSummary, userId: UUID) {
        launchRequest {
            repository.changeRole(group.conversationId, userId, "ADMIN")
            refreshData()
        }
    }

    fun demoteMember(group: GroupSummary, userId: UUID) {
        launchRequest {
            repository.changeRole(group.conversationId, userId, "MEMBER")
            refreshData()
        }
    }

    fun transferOwner(group: GroupSummary, userId: UUID) {
        launchRequest {
            repository.transferOwner(group.conversationId, userId)
            refreshData()
        }
    }

    fun updateProfile(group: GroupSummary) {
        val current = _state.value
        launchRequest {
            repository.updateProfile(
                group.conversationId,
                current.name.trim(),
                current.description.trim(),
                current.visibility,
            )
            refreshData()
        }
    }

    fun leave(group: GroupSummary) {
        launchRequest {
            repository.leave(group.conversationId)
            refreshData()
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
            refreshData()
        }
    }

    fun rejectJoinRequest(request: GroupJoinRequestSummary) {
        launchRequest {
            repository.rejectJoinRequest(request.conversationId, request.requestId)
            refreshJoinRequests(request.conversationId)
        }
    }

    private suspend fun refreshJoinRequests(conversationId: UUID) {
        _state.value = _state.value.copy(
            joinRequestGroupId = conversationId,
            joinRequests = repository.listJoinRequests(conversationId),
        )
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
        _state.value = _state.value.copy(groups = repository.list())
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GroupViewModel(repository) as T
    }
}
