package com.jitong.im.desktop

import com.jitong.im.desktop.local.LocalMessage
import kotlin.test.Test
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

    private fun message(
        type: String,
        localState: String,
        state: String = "ACTIVE",
    ) = LocalMessage(
        messageId = "11111111-1111-4111-8111-111111111111",
        conversationId = "conversation-1",
        senderId = "user-2",
        clientMsgId = "client-1",
        conversationSeq = 1,
        type = type,
        state = state,
        localState = localState,
        text = "hello",
        serverAcceptedAt = "2026-08-23T00:00:00Z",
        createdAt = 1,
    )
}
