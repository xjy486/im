package com.jitong.im.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jitong.im.android.local.LocalMessageEntity
import com.jitong.im.android.message.MessageRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal data class MessageUiState(
    val conversationId: UUID? = null,
    val currentUserId: UUID? = null,
    val messages: List<LocalMessageEntity> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<LocalMessageEntity> = emptyList(),
    val searchLoading: Boolean = false,
    val draft: String = "",
    val loading: Boolean = false,
    val message: String? = null,
)

internal class MessageViewModel(
    private val repository: MessageRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MessageUiState())
    val state: StateFlow<MessageUiState> = _state.asStateFlow()
    private var observeJob: Job? = null

    fun open(conversationId: UUID) {
        if (_state.value.conversationId == conversationId && observeJob?.isActive == true) {
            return
        }
        observeJob?.cancel()
        _state.value = _state.value.copy(
            conversationId = conversationId,
            currentUserId = null,
            messages = emptyList(),
            loading = true,
            message = null,
        )
        observeJob = viewModelScope.launch {
            _state.value = _state.value.copy(currentUserId = repository.currentUserId())
            launch {
                runCatching { repository.openConversation(conversationId) }
                    .onFailure { _state.value = _state.value.copy(message = "历史消息加载失败") }
                    .also { _state.value = _state.value.copy(loading = false) }
            }
            repository.observe(conversationId).collect { messages ->
                _state.value = _state.value.copy(messages = messages)
            }
        }
    }

    fun setDraft(value: String) {
        _state.value = _state.value.copy(draft = value.take(4000), message = null)
    }

    fun setSearchQuery(value: String) {
        _state.value = _state.value.copy(searchQuery = value.take(200), message = null)
    }

    fun search() {
        val query = _state.value.searchQuery
        viewModelScope.launch {
            _state.value = _state.value.copy(searchLoading = true, message = null)
            runCatching { repository.search(query) }
                .onSuccess { results ->
                    _state.value = _state.value.copy(searchResults = results)
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        searchResults = emptyList(),
                        message = "本地搜索失败",
                    )
                }
            _state.value = _state.value.copy(searchLoading = false)
        }
    }

    fun clearSearch() {
        _state.value = _state.value.copy(searchQuery = "", searchResults = emptyList())
    }

    fun markRead(readSeq: Long) {
        val conversationId = _state.value.conversationId ?: return
        viewModelScope.launch {
            runCatching { repository.markRead(conversationId, readSeq) }
                .onFailure { _state.value = _state.value.copy(message = "已读状态同步失败") }
        }
    }

    fun send() {
        val conversationId = _state.value.conversationId ?: return
        val text = _state.value.draft
        if (text.isBlank()) {
            _state.value = _state.value.copy(message = "请输入消息")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(message = null)
            runCatching { repository.send(conversationId, text) }
                .onSuccess { _state.value = _state.value.copy(draft = "") }
                .onFailure { _state.value = _state.value.copy(message = "消息发送失败，请稍后重试") }
        }
    }

    fun sendImage(source: ByteArray) {
        val conversationId = _state.value.conversationId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(message = null)
            runCatching { repository.sendImage(conversationId, source) }
                .onFailure { _state.value = _state.value.copy(message = "图片发送失败，请稍后重试") }
        }
    }

    suspend fun loadMedia(message: LocalMessageEntity, thumbnail: Boolean): ByteArray? =
        repository.loadMedia(message, thumbnail)

    fun recall(message: LocalMessageEntity) {
        viewModelScope.launch {
            runCatching { repository.recall(message) }
                .onFailure { _state.value = _state.value.copy(message = "消息撤回失败，请稍后重试") }
        }
    }

    fun retry(clientMsgId: UUID) {
        viewModelScope.launch {
            runCatching { repository.retryPending(clientMsgId) }
                .onFailure { _state.value = _state.value.copy(message = "消息重试失败，请稍后重试") }
        }
    }

    class Factory(private val repository: MessageRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MessageViewModel(repository) as T
    }
}
