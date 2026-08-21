package com.jitong.im.desktop.local

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalDatabaseSearchTest {
    @Test
    fun search_returns_english_matches_case_insensitively() {
        val manager = LocalDatabaseManager(createTempDirectory("jitong-search"), InMemoryKeychain())
        manager.open("12345678903").use { database ->
            database.upsertMessage(message("message-1", "Hello from Android"))
            database.upsertMessage(message("message-2", "A different message"))

            assertEquals(
                listOf("message-1"),
                database.searchMessages("HELLO").map { it.messageId },
            )
        }
    }

    @Test
    fun search_requires_the_original_continuous_chinese_text_after_bigram_lookup() {
        val manager = LocalDatabaseManager(createTempDirectory("jitong-search-cjk"), InMemoryKeychain())
        manager.open("12345678903").use { database ->
            database.upsertMessage(message("message-1", "请说你好世界"))
            database.upsertMessage(message("message-2", "请说你好，世界"))

            assertEquals(
                listOf("message-1"),
                database.searchMessages("你好世界").map { it.messageId },
            )
        }
    }

    @Test
    fun single_chinese_character_searches_the_original_text() {
        val manager = LocalDatabaseManager(createTempDirectory("jitong-search-single-cjk"), InMemoryKeychain())
        manager.open("12345678903").use { database ->
            database.upsertMessage(message("message-1", "你好"))
            database.upsertMessage(message("message-2", "再见"))

            assertEquals(
                listOf("message-1"),
                database.searchMessages("你").map { it.messageId },
            )
        }
    }

    @Test
    fun recalled_moderated_and_nonaccepted_messages_are_not_searchable() {
        val manager = LocalDatabaseManager(createTempDirectory("jitong-search-state"), InMemoryKeychain())
        manager.open("12345678903").use { database ->
            database.upsertMessage(message("active", "secret active"))
            database.upsertMessage(message("recalled", "secret recalled", state = "RECALLED"))
            database.upsertMessage(message("moderated", "secret moderated", state = "MODERATED"))
            database.upsertMessage(message("queued", "secret queued", localState = "QUEUED"))

            assertEquals(
                listOf("active"),
                database.searchMessages("secret").map { it.messageId },
            )
            assertTrue(database.searchMessages("recalled").isEmpty())
        }
    }

    private fun message(
        messageId: String,
        text: String,
        state: String = "ACTIVE",
        localState: String = "RECEIVED",
    ) = LocalMessage(
        messageId = messageId,
        conversationId = "conversation-1",
        senderId = "user-1",
        clientMsgId = "client-$messageId",
        conversationSeq = messageId.hashCode().toLong().let { kotlin.math.abs(it) },
        type = "TEXT",
        state = state,
        localState = localState,
        text = text,
        serverAcceptedAt = "2026-08-21T00:00:00Z",
        createdAt = 1,
    )
}
