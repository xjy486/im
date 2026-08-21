package com.jitong.im.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jitong.im.android.group.GroupRepository
import com.jitong.im.android.group.GroupSearchResult
import com.jitong.im.android.group.GroupSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class GroupUiState(
    val loading: Boolean = false,
    val name: String = "",
    val description: String = "",
    val visibility: String = "PUBLIC",
    val avatar: ByteArray? = null,
    val groups: List<GroupSummary> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<GroupSearchResult> = emptyList(),
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
