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
import com.jitong.im.android.message.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

internal data class ContactUiState(
    val loading: Boolean = false,
    val searchAccountNo: String = "",
    val searchResult: ContactSearchResult? = null,
    val contacts: List<ContactSummary> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
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

    init {
        viewModelScope.launch {
            merge<ContactChangeEvent>(
                messageRepository.relationshipChanges.map { ContactChangeEvent.Relationship(it) },
                messageRepository.contactRequestChanges.map { ContactChangeEvent.Refresh },
            ).collect { event ->
                if (event is ContactChangeEvent.Relationship) {
                    _state.value = _state.value.applyRelationshipChange(event.change)
                } else {
                    runCatching { refreshData() }
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
            refreshData()
        }
    }

    fun accept(requestId: UUID) {
        launchRequest {
            repository.accept(requestId)
            refreshData()
        }
    }

    fun reject(requestId: UUID) {
        launchRequest {
            repository.reject(requestId)
            refreshData()
        }
    }

    fun cancel(requestId: UUID) {
        launchRequest {
            repository.cancel(requestId)
            refreshData()
        }
    }

    fun remove(userId: UUID) {
        launchRequest {
            repository.remove(userId)
            refreshData()
        }
    }

    fun block(userId: UUID) {
        launchRequest {
            repository.block(userId)
            refreshData()
        }
    }

    fun unblock(userId: UUID) {
        launchRequest {
            repository.unblock(userId)
            refreshData()
        }
    }

    fun refresh() {
        launchRequest {
            refreshData()
        }
    }

    private suspend fun refreshData() {
        val contacts = repository.contacts()
        _state.value = _state.value.copy(
            contacts = contacts,
            conversations = repository.conversations(),
            requests = repository.requests(),
        )
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

    data object Refresh : ContactChangeEvent
}
