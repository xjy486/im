package com.jitong.im.desktop.local

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalDatabaseMessageTest {
    @Test
    fun accepted_message_replaces_pending_local_copy_without_duplicates() {
        val manager = LocalDatabaseManager(createTempDirectory("jitong-message"), InMemoryKeychain())
        manager.open("12345678903").use { database ->
            database.upsertMessage(
                LocalMessage(
                    messageId = "local:client-1",
                    conversationId = "conversation-1",
                    senderId = "user-1",
                    clientMsgId = "client-1",
                    conversationSeq = null,
                    type = "TEXT",
                    state = "ACTIVE",
                    localState = "SENDING",
                    text = "hello",
                    mediaId = null,
                    serverAcceptedAt = null,
                    createdAt = 1))
            database.replaceMessageByClientId(
                LocalMessage(
                    messageId = "message-1",
                    conversationId = "conversation-1",
                    senderId = "user-1",
                    clientMsgId = "client-1",
                    conversationSeq = 1,
                    type = "TEXT",
                    state = "ACTIVE",
                    localState = "SENT",
                    text = "hello",
                    mediaId = null,
                    serverAcceptedAt = "2026-08-20T00:00:00Z",
                    createdAt = 2))

            assertEquals(
                listOf("message-1"),
                database.listMessages("conversation-1").map { it.messageId })
            assertEquals(1, database.lastConversationSeq("conversation-1"))
        }
    }

    @Test
    fun image_media_id_survives_a_desktop_database_restart() {
        val root = createTempDirectory("jitong-image-message")
        val manager = LocalDatabaseManager(root, InMemoryKeychain())

        manager.open("12345678903").use { database ->
            database.upsertMessage(
                LocalMessage(
                    messageId = "message-1",
                    conversationId = "conversation-1",
                    senderId = "user-1",
                    clientMsgId = "client-1",
                    conversationSeq = 1,
                    type = "IMAGE",
                    state = "ACTIVE",
                    localState = "RECEIVED",
                    text = "",
                    mediaId = "media-1",
                    serverAcceptedAt = "2026-08-20T00:00:00Z",
                    createdAt = 1))
        }

        manager.open("12345678903").use { database ->
            val message = database.listMessages("conversation-1").single()
            assertEquals("IMAGE", message.type)
            assertEquals("media-1", message.mediaId)
            assertNull(message.text.takeIf { it.isNotEmpty() })
        }
    }
}
