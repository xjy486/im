package com.jitong.im.android.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jitong.im.search.LocalSearchText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountDatabaseSearchTest {
    private lateinit var database: AccountDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AccountDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        database.conversationDao().upsert(
            LocalConversationEntity(
                conversationId = CONVERSATION_ID,
                peerUserId = "user-2",
                peerAccountNo = "22345678902",
                peerDisplayName = "Bob",
                status = "ACTIVE",
                relationship = "ACTIVE",
                lastSequence = 3,
                updatedAt = 1,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun fts_matches_continuous_chinese_and_single_character_queries() {
        database.messageDao().upsert(message("continuous", "请说你好世界", 1))
        database.messageDao().upsert(message("punctuated", "请说你好，世界", 2))
        database.messageDao().upsert(message("single", "你好吗", 3))

        val plan = LocalSearchText.plan("你好世界")!!
        assertEquals(
            listOf("continuous"),
            database.messageDao()
                .searchIndexed(null, plan.ftsMatch, plan.normalizedQuery, 100)
                .map { it.messageId },
        )
        assertEquals(
            listOf("single", "continuous"),
            database.messageDao()
                .searchSingleCjk(null, "你", 100)
                .map { it.messageId },
        )
    }

    @Test
    fun recalled_and_invisible_conversation_history_is_not_searchable() {
        database.messageDao().upsert(message("active", "secret active", 1))
        database.messageDao().upsert(message("recalled", "secret recalled", 2, state = "RECALLED"))
        database.messageDao().upsert(message("moderated", "secret moderated", 3, state = "MODERATED"))

        val plan = LocalSearchText.plan("secret")!!
        assertEquals(
            listOf("active"),
            database.messageDao()
                .searchIndexed(null, plan.ftsMatch, plan.normalizedQuery, 100)
                .map { it.messageId },
        )

        database.conversationDao().updateSearchVisibility(
            conversationId = CONVERSATION_ID,
            visible = false,
            visibleAfterSeq = 0,
            updatedAt = 2,
        )
        assertTrue(
            database.messageDao()
                .searchIndexed(null, plan.ftsMatch, plan.normalizedQuery, 100)
                .isEmpty(),
        )
    }

    @Test
    fun common_english_search_stays_under_200ms_p95_for_100k_messages() {
        database.runInTransaction {
            (1L..100_000L).forEach { sequence ->
                database.messageDao().upsert(
                    message(
                        id = "performance-$sequence",
                        text = "common keyword message $sequence",
                        sequence = sequence,
                    ),
                )
            }
        }

        repeat(5) {
            database.messageDao().searchIndexed(
                null,
                "wcommon",
                "common",
                100,
            )
        }
        val samples = List(20) {
            val started = System.nanoTime()
            database.messageDao().searchIndexed(
                null,
                "wcommon",
                "common",
                100,
            )
            System.nanoTime() - started
        }.sorted()
        val p95Nanos = samples[(samples.size * 95 / 100).coerceAtMost(samples.lastIndex)]
        assertTrue(
            "search p95 was ${p95Nanos / 1_000_000.0} ms for 100k local text messages",
            p95Nanos < 200_000_000L,
        )
    }

    private fun message(
        id: String,
        text: String,
        sequence: Long,
        state: String = "ACTIVE",
    ) = LocalMessageEntity(
        messageId = id,
        conversationId = CONVERSATION_ID,
        senderId = "user-1",
        clientMsgId = "client-$id",
        conversationSeq = sequence,
        type = "TEXT",
        state = state,
        localState = "RECEIVED",
        text = text,
        serverAcceptedAt = "2026-08-21T00:00:00Z",
        createdAt = sequence,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation-1"
    }
}
