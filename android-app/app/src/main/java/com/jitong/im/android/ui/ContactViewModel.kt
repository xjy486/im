package com.jitong.im.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jitong.im.android.contact.ContactRepository
import com.jitong.im.android.contact.ContactRelationshipChange
import com.jitong.im.android.contact.ContactRequestSummary
import com.jitong.im.android.contact.ContactSearchResult
import com.jitong.im.android.contact.ContactSummary
import com.jitong.im.android.contact.ConversationSummary
import com.jitong.im.android.contact.sortedForMessageList
import com.jitong.im.android.message.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal enum class ContactRequestAction {
    ACCEPT,
    REJECT,
    CANCEL,
}

internal fun contactRequestActions(
    request: ContactRequestSummary,
): List<ContactRequestAction> =
    when {
        request.status != "PENDING" -> emptyList()
        request.incoming -> listOf(
            ContactRequestAction.ACCEPT,
            ContactRequestAction.REJECT,
        )
        else -> listOf(ContactRequestAction.CANCEL)
    }

internal data class ContactUiState(
    val loading: Boolean = false,
    val searchAccountNo: String = "",
    val searchResult: ContactSearchResult? = null,
    val contacts: List<ContactSummary> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val messageListConversations: List<ConversationSummary> = emptyList(),
    val requests: List<ContactRequestSummary> = emptyList(),
    val message: String? = null,
)

internal fun ContactUiState.applyRelationshipChange(
    change: ContactRelationshipChange,
): ContactUiState {
    val changedConversation = conversations
        .firstOrNull { it.conversationId == change.conversationId }
        ?: return this
    val changedPeerUserId = changedConversation.peerUserId
    return copy(
        contacts = contacts.filterNot { it.userId == changedPeerUserId },
        conversations = conversations.map {
            if (it.conversationId == change.conversationId) {
                it.copy(
                    status = change.status,
                    relationship = change.relationship,
                )
            } else {
                it
            }
        },
    )
}

internal class ContactViewModel(
    private val repository: ContactRepository,
    private val messageRepository: MessageRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ContactUiState())
    val state: StateFlow<ContactUiState> = _state.asStateFlow()
    private val refreshMutex = Mutex()

    init {
        viewModelScope.launch {
            merge<ContactChangeEvent>(
                messageRepository.relationshipChanges.map { ContactChangeEvent.Relationship(it) },
                messageRepository.contactRequestChanges.map { ContactChangeEvent.Refresh },
                messageRepository.conversationChanges.map { ContactChangeEvent.ConversationPreviewChanged },
            ).collect { event ->
                if (event is ContactChangeEvent.Relationship) {
                    runCatching { refreshLatest() }
                } else if (event is ContactChangeEvent.ConversationPreviewChanged) {
                    runCatching { refreshConversationPreviews() }
                } else {
                    runCatching { refreshLatest() }
                }
            }
        }
    }

    fun setSearchAccountNo(value: String) {
        _state.value = _state.value.copy(searchAccountNo = value.filter(Char::isDigit).take(11), message = null)
    }

    fun search() {
        val accountNo = _state.value.searchAccountNo
        if (accountNo.length != 11) {
            _state.value = _state.value.copy(message = "请输入完整 11 位账号")
            return
        }
        launchRequest {
            _state.value = _state.value.copy(searchResult = repository.search(accountNo))
        }
    }

    fun addContact(accountNo: String) {
        launchRequest {
            repository.createRequest(accountNo, "")
            refreshLatest()
        }
    }

    fun accept(requestId: UUID) {
        launchRequest {
            repository.accept(requestId)
            refreshLatest()
        }
    }

    fun reject(requestId: UUID) {
        launchRequest {
            repository.reject(requestId)
            refreshLatest()
        }
    }

    fun cancel(requestId: UUID) {
        launchRequest {
            repository.cancel(requestId)
            refreshLatest()
        }
    }

    fun remove(userId: UUID) {
        launchRequest {
            repository.remove(userId)
            refreshLatest()
        }
    }

    fun block(userId: UUID) {
        launchRequest {
            repository.block(userId)
            refreshLatest()
        }
    }

    fun unblock(userId: UUID) {
        launchRequest {
            repository.unblock(userId)
            refreshLatest()
        }
    }

    fun refresh() {
        launchRequest {
            refreshLatest()
        }
    }

    fun hideConversationFromMessageList(
        conversationId: UUID,
        hiddenAfterSequence: Long,
    ) {
        viewModelScope.launch {
            runCatching {
                messageRepository.hideConversationFromMessageList(
                    conversationId,
                    hiddenAfterSequence,
                )
                _state.value = _state.value.copy(
                    messageListConversations = _state.value.messageListConversations
                        .filterNot { it.conversationId == conversationId },
                )
            }.onFailure {
                _state.value = _state.value.copy(message = "删除会话失败，请稍后重试")
            }
        }
    }

    internal suspend fun refreshLatest() {
        refreshMutex.withLock {
            val current = _state.value
            val requests = repository.requests()
            val contacts = repository.contacts()
            val conversations = repository.conversations()
            val messageListConversations = messageRepository
                .filterC2cConversationsForMessageList(conversations)
                .sortedForMessageList()
            _state.value = current.copy(
                contacts = contacts,
                conversations = conversations,
                messageListConversations = messageListConversations,
                requests = requests,
            )
        }
    }

    internal suspend fun refreshConversationPreviews() {
        val conversations = repository.conversations()
        val messageListConversations = messageRepository
            .filterC2cConversationsForMessageList(conversations)
            .sortedForMessageList()
        _state.value = _state.value.copy(
            conversations = conversations,
            messageListConversations = messageListConversations,
        )
    }

    internal fun refreshNow() {
        launchRequest {
            refreshLatest()
        }
    }

    private fun launchRequest(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            runCatching { block() }
                .onFailure { _state.value = _state.value.copy(message = "操作失败，请稍后重试") }
            _state.value = _state.value.copy(loading = false)
        }
    }

    class Factory(
        private val repository: ContactRepository,
        private val messageRepository: MessageRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ContactViewModel(repository, messageRepository) as T
    }
}

private sealed interface ContactChangeEvent {
    data class Relationship(val change: ContactRelationshipChange) : ContactChangeEvent

    data object ConversationPreviewChanged : ContactChangeEvent

    data object Refresh : ContactChangeEvent
}
