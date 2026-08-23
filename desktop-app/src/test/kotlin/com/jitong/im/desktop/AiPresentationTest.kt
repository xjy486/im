package com.jitong.im.desktop

import com.jitong.im.desktop.local.LocalMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiPresentationTest {
    @Test
    fun received_text_and_images_are_valid_private_ai_evidence_but_pending_messages_are_not() {
        assertTrue(message(type = "TEXT", localState = "RECEIVED").isEligibleForAiEvidence())
        assertTrue(message(type = "IMAGE", localState = "RECEIVED").isEligibleForAiEvidence())
        assertFalse(message(type = "SYSTEM", localState = "RECEIVED").isEligibleForAiEvidence())
        assertFalse(message(type = "TEXT", localState = "SENDING").isEligibleForAiEvidence())
        assertFalse(
            message(type = "TEXT", localState = "RECEIVED", state = "RECALLED")
                .isEligibleForAiEvidence())
    }

    @Test
    fun summary_range_keeps_messages_that_arrive_while_an_earlier_summary_is_running() {
        val summarized = DesktopAiSummaryRange(afterSeq = 4, untilSeq = 6)
            .summarizedThrough(6)
        val outgoingOnly = summarized.includeNewMessages(
            messages = listOf(message(type = "TEXT", localState = "SENT", senderId = "user-1", seq = 7)),
            currentUserId = "user-1")

        assertFalse(outgoingOnly.canRequest)

        val requestRange = outgoingOnly.includeNewMessages(
            messages = listOf(
                message(type = "TEXT", localState = "SENT", senderId = "user-1", seq = 7),
                message(type = "TEXT", localState = "RECEIVED", senderId = "user-2", seq = 8)),
            currentUserId = "user-1")
        val completedAfterAnotherArrival = requestRange
            .includeNewMessages(
                messages = listOf(
                    message(
                        type = "TEXT",
                        localState = "RECEIVED",
                        senderId = "user-2",
                        seq = 9)),
                currentUserId = "user-1")
            .summarizedThrough(requestRange.untilSeq)

        assertEquals(8, completedAfterAnotherArrival.afterSeq)
        assertEquals(9, completedAfterAnotherArrival.untilSeq)
        assertTrue(completedAfterAnotherArrival.canRequest)
    }

    @Test
    fun disabling_ai_blocks_new_requests_but_keeps_existing_results_manageable() {
        val disabled = desktopAiPresentationPolicy(aiEnabled = false)
        val enabled = desktopAiPresentationPolicy(aiEnabled = true)

        assertFalse(disabled.canRequestNewContent)
        assertTrue(disabled.canManageExistingContent)
        assertTrue(enabled.canRequestNewContent)
        assertTrue(enabled.canManageExistingContent)
    }

    private fun message(
        type: String,
        localState: String,
        state: String = "ACTIVE",
        senderId: String = "user-2",
        seq: Long = 1,
    ) = LocalMessage(
        messageId = "11111111-1111-4111-8111-111111111111",
        conversationId = "conversation-1",
        senderId = senderId,
        clientMsgId = "client-1",
        conversationSeq = seq,
        type = type,
        state = state,
        localState = localState,
        text = "hello",
        serverAcceptedAt = "2026-08-23T00:00:00Z",
        createdAt = 1,
    )
}
