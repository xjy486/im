package com.jitong.im.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import com.jitong.im.android.ai.AiDraft
import com.jitong.im.android.ai.AiRepository
import com.jitong.im.android.local.LocalAiActionItemEntity
import com.jitong.im.android.local.LocalAiArtifactEntity
import com.jitong.im.android.message.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal data class AiDraftUi(val text: String, val tone: String)
internal data class AiKeyFactUi(
    val artifactId: String,
    val category: String,
    val content: String,
    val confidence: Double,
    val sourceMessageIds: List<String>,
)

internal data class AiUiState(
    val conversationId: UUID? = null,
    val consentEnabled: Boolean = false,
    val enabledForBoth: Boolean = false,
    val selectedMessageIds: Set<UUID> = emptySet(),
    val drafts: List<AiDraftUi> = emptyList(),
    val keyFacts: List<AiKeyFactUi> = emptyList(),
    val actionItems: List<LocalAiActionItemEntity> = emptyList(),
    val artifacts: List<LocalAiArtifactEntity> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

internal class AiViewModel(
    private val repository: AiRepository,
    private val messageRepository: MessageRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AiUiState())
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            messageRepository.aiPolicyChanges.collect { conversationId ->
                if (_state.value.conversationId == conversationId) {
                    refreshConsent(conversationId)
                }
            }
        }
    }

    fun open(conversationId: UUID) {
        if (_state.value.conversationId != conversationId) {
            _state.value = AiUiState(conversationId = conversationId)
        }
        viewModelScope.launch {
            refreshConsent(conversationId)
            refreshLocal()
        }
    }

    fun updateConsent(enabled: Boolean) = launchOperation {
        val conversationId = requireNotNull(_state.value.conversationId)
        val consent = repository.updateConsent(conversationId, enabled)
        _state.value = _state.value.copy(
            consentEnabled = consent.enabled,
            enabledForBoth = consent.enabledForBoth,
            message = null,
        )
    }

    fun toggleMessage(messageId: UUID) {
        val selected = _state.value.selectedMessageIds.toMutableSet()
        if (!selected.add(messageId)) selected.remove(messageId)
        _state.value = _state.value.copy(selectedMessageIds = selected)
    }

    fun requestSmartReplies() = launchOperation {
        val conversationId = requireNotNull(_state.value.conversationId)
        val drafts = repository.requestSmartReplies(conversationId)
        _state.value = _state.value.copy(drafts = drafts.map { it.toUi() })
        refreshLocal()
    }

    fun updateDraft(index: Int, text: String) {
        _state.value = _state.value.copy(
            drafts = _state.value.drafts.mapIndexed { itemIndex, draft ->
                if (itemIndex == index) draft.copy(text = text.take(4000)) else draft
            },
        )
    }

    fun extractSelected() = launchOperation {
        val conversationId = requireNotNull(_state.value.conversationId)
        val selected = _state.value.selectedMessageIds.toList()
        require(selected.isNotEmpty()) { "请先选择消息" }
        repository.requestExtraction(conversationId, selected)
        _state.value = _state.value.copy(selectedMessageIds = emptySet())
        refreshLocal()
    }

    fun deleteArtifact(artifactId: String) = launchOperation {
        repository.deleteArtifact(UUID.fromString(artifactId))
        refreshLocal()
    }

    fun setActionItemCompleted(actionItemId: String, completed: Boolean) = launchOperation {
        repository.updateActionItem(
            UUID.fromString(actionItemId),
            if (completed) "COMPLETED" else "OPEN",
        )
        refreshLocal()
    }

    fun deleteActionItem(actionItemId: String) = launchOperation {
        repository.deleteActionItem(UUID.fromString(actionItemId))
        refreshLocal()
    }

    fun clearForLogout() {
        _state.value = AiUiState()
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            runCatching { block() }
                .onFailure { _state.value = _state.value.copy(message = it.message ?: "AI 操作失败") }
            _state.value = _state.value.copy(loading = false)
        }
    }

    private suspend fun refreshLocal() {
        val conversationId = _state.value.conversationId ?: return
        val artifacts = repository.artifacts(conversationId)
        val actionItems = repository.actionItems(conversationId)
        val drafts = artifacts.firstOrNull { it.artifactType == "SMART_REPLY" }
            ?.let(::parseDrafts)
            ?: _state.value.drafts
        _state.value = _state.value.copy(
            artifacts = artifacts,
            drafts = drafts,
            keyFacts = artifacts.filter { it.artifactType == "EXTRACTION" }.flatMap(::parseFacts),
            actionItems = actionItems,
        )
    }

    private suspend fun refreshConsent(conversationId: UUID) {
        runCatching { repository.consent(conversationId) }
            .onSuccess { consent ->
                if (_state.value.conversationId == conversationId) {
                    _state.value = _state.value.copy(
                        consentEnabled = consent.enabled,
                        enabledForBoth = consent.enabledForBoth,
                    )
                }
            }
    }

    private fun parseDrafts(artifact: LocalAiArtifactEntity): List<AiDraftUi> = runCatching {
        JsonParser.parseString(artifact.contentJson).asJsonObject
            .getAsJsonArray("replies")
            .map { value ->
                val draft = value.asJsonObject
                AiDraftUi(draft.get("text").asString, draft.get("tone").asString)
            }
    }.getOrDefault(emptyList())

    private fun parseFacts(artifact: LocalAiArtifactEntity): List<AiKeyFactUi> = runCatching {
        JsonParser.parseString(artifact.contentJson).asJsonObject
            .getAsJsonArray("keyFacts")
            .map { value ->
                val fact = value.asJsonObject
                AiKeyFactUi(
                    artifactId = artifact.artifactId,
                    category = fact.get("category").asString,
                    content = fact.get("content").asString,
                    confidence = fact.get("confidence").asDouble,
                    sourceMessageIds = fact.getAsJsonArray("sourceMessageIds").map { it.asString },
                )
            }
    }.getOrDefault(emptyList())

    private fun AiDraft.toUi() = AiDraftUi(text, tone)

    class Factory(
        private val repository: AiRepository,
        private val messageRepository: MessageRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiViewModel(repository, messageRepository) as T
    }
}
