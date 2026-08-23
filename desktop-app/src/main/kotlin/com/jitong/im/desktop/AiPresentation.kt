package com.jitong.im.desktop

import com.jitong.im.desktop.local.LocalMessage
import java.util.UUID

internal data class DesktopAiSummaryRange(
    val afterSeq: Long,
    val untilSeq: Long,
) {
    val canRequest: Boolean
        get() = untilSeq > afterSeq

    fun includeNewMessages(
        messages: List<LocalMessage>,
        currentUserId: String,
    ): DesktopAiSummaryRange {
        val hasNewIncomingMessage = messages.any { message ->
            message.senderId != currentUserId
                && message.state == "ACTIVE"
                && (message.conversationSeq ?: Long.MIN_VALUE) > untilSeq
        }
        if (!hasNewIncomingMessage) return this
        val latestSequence = messages.mapNotNull(LocalMessage::conversationSeq).maxOrNull()
            ?: return this
        return copy(untilSeq = maxOf(untilSeq, latestSequence))
    }

    fun summarizedThrough(sequence: Long): DesktopAiSummaryRange = copy(
        afterSeq = maxOf(afterSeq, minOf(sequence, untilSeq)))
}

internal data class DesktopAiPresentationPolicy(
    val canRequestNewContent: Boolean,
    val canManageExistingContent: Boolean,
)

internal fun desktopAiPresentationPolicy(aiEnabled: Boolean) =
    DesktopAiPresentationPolicy(
        canRequestNewContent = aiEnabled,
        canManageExistingContent = true)

internal fun LocalMessage.isEligibleForAiEvidence(): Boolean =
    state == "ACTIVE"
        && localState != "SENDING"
        && type in setOf("TEXT", "IMAGE")
        && runCatching { UUID.fromString(messageId) }.isSuccess
