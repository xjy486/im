package com.jitong.im.desktop.local

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

class LocalDatabaseSearchPerformanceTest {
    @Test
    fun common_english_search_stays_under_200ms_p95_for_100k_messages() {
        val manager = LocalDatabaseManager(
            createTempDirectory("jitong-search-performance"),
            InMemoryKeychain(),
        )
        manager.open("12345678903").use { database ->
            database.upsertConversation(
                LocalConversation(
                    conversationId = "conversation-1",
                    peerUserId = "user-2",
                    peerAccountNo = "22345678902",
                    peerDisplayName = "Bob",
                    status = "ACTIVE",
                    relationship = "ACTIVE",
                    blockedByMe = false,
                    readSeq = 0,
                    peerReadSeq = 0,
                    updatedAt = 1,
                ),
            )
            database.upsertMessages(
                (1L..100_000L).map { sequence ->
                    LocalMessage(
                        messageId = "message-$sequence",
                        conversationId = "conversation-1",
                        senderId = "user-1",
                        clientMsgId = "client-$sequence",
                        conversationSeq = sequence,
                        type = "TEXT",
                        state = "ACTIVE",
                        localState = "RECEIVED",
                        text = "common keyword message $sequence",
                        serverAcceptedAt = "2026-08-21T00:00:00Z",
                        createdAt = sequence,
                    )
                },
            )

            repeat(5) { database.searchMessages("common") }
            val samples = List(20) {
                measureTime { database.searchMessages("common", limit = 100) }
            }.sorted()

            val p95 = samples[(samples.size * 95 / 100).coerceAtMost(samples.lastIndex)]
            assertTrue(
                p95 < 200.milliseconds,
                "search p95 was $p95 for 100k local text messages",
            )
        }
    }
}
