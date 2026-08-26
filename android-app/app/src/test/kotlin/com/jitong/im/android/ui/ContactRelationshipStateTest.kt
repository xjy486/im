package com.jitong.im.android.ui

import com.jitong.im.android.contact.ContactRelationshipChange
import com.jitong.im.android.contact.ContactRequestSummary
import com.jitong.im.android.contact.ContactSummary
import com.jitong.im.android.contact.ConversationSummary
import com.jitong.im.android.contact.messageListPreview
import com.jitong.im.android.contact.sortedForMessageList
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactRelationshipStateTest {

    @Test
    fun image_latest_message_uses_image_preview_text() {
        val preview = ConversationSummary(
            version = 1,
            conversationId = UUID.randomUUID(),
            peerUserId = UUID.randomUUID(),
            peerAccountNo = "12345678901",
            peerDisplayName = "Alice",
            status = "ACTIVE",
            relationship = "ACTIVE",
            blockedByMe = false,
            latestMessage = com.jitong.im.android.contact.ConversationPreview(
                conversationSeq = 1,
                type = "IMAGE",
                text = "",
                state = "ACTIVE",
                serverAcceptedAt = "2026-08-26T00:00:01Z",
            ),
        )

        assertEquals("图片", preview.messageListPreview())
    }

    @Test
    fun message_list_orders_conversations_by_latest_message() {
        val older = conversationWithPreview("Older", 100, "2026-08-26T00:00:01Z")
        val newer = conversationWithPreview("Newer", 2, "2026-08-26T00:00:02Z")

        assertEquals(
            listOf("Newer", "Older"),
            listOf(older, newer).sortedForMessageList().map { it.peerDisplayName },
        )
    }

    @Test
    fun pending_incoming_request_exposes_recipient_actions() {
        val request = ContactRequestSummary(
            version = 1,
            requestId = UUID.randomUUID(),
            requesterId = UUID.randomUUID(),
            recipientId = UUID.randomUUID(),
            status = "PENDING",
            verification = "",
            expiresAt = "2026-08-27T00:00:00Z",
            incoming = true,
            peerAccountNo = "12345678901",
            peerDisplayName = "Alice",
        )

        assertEquals(
            listOf(ContactRequestAction.ACCEPT, ContactRequestAction.REJECT),
            contactRequestActions(request),
        )
    }

    @Test
    fun resolved_incoming_request_does_not_expose_recipient_actions() {
        val request = ContactRequestSummary(
            version = 1,
            requestId = UUID.randomUUID(),
            requesterId = UUID.randomUUID(),
            recipientId = UUID.randomUUID(),
            status = "ACCEPTED",
            verification = "",
            expiresAt = "2026-08-27T00:00:00Z",
            incoming = true,
            peerAccountNo = "12345678901",
            peerDisplayName = "Alice",
        )

        assertTrue(contactRequestActions(request).isEmpty())
    }

    @Test
    fun removing_a_contact_immediately_hides_the_contact_and_disables_the_c2c_conversation() {
        val peerUserId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val state = ContactUiState(
            contacts = listOf(
                ContactSummary(
                    version = 1,
                    userId = peerUserId,
                    accountNo = "12345678901",
                    displayName = "Bob",
                    conversationId = conversationId,
                    relationship = "ACTIVE",
                ),
            ),
            conversations = listOf(
                ConversationSummary(
                    version = 1,
                    conversationId = conversationId,
                    peerUserId = peerUserId,
                    peerAccountNo = "12345678901",
                    peerDisplayName = "Bob",
                    status = "ACTIVE",
                    relationship = "ACTIVE",
                    blockedByMe = false,
                ),
            ),
        )

        val actual = state.applyRelationshipChange(
            ContactRelationshipChange(
                conversationId = conversationId,
                status = "READ_ONLY",
                relationship = "READ_ONLY",
            ),
        )

        assertEquals(emptyList(), actual.contacts)
        assertEquals("READ_ONLY", actual.conversations.single().status)
        assertEquals("READ_ONLY", actual.conversations.single().relationship)
    }

    private fun conversationWithPreview(
        name: String,
        sequence: Long,
        acceptedAt: String,
    ) = ConversationSummary(
        version = 1,
        conversationId = UUID.randomUUID(),
        peerUserId = UUID.randomUUID(),
        peerAccountNo = "12345678901",
        peerDisplayName = name,
        status = "ACTIVE",
        relationship = "ACTIVE",
        blockedByMe = false,
        latestMessage = com.jitong.im.android.contact.ConversationPreview(
            conversationSeq = sequence,
            type = "TEXT",
            text = name,
            state = "ACTIVE",
            serverAcceptedAt = acceptedAt,
        ),
    )
}
