package com.jitong.im.android.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteStatement
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
    private var performanceDatabase: AccountDatabase? = null

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
        performanceDatabase?.close()
        ApplicationProvider.getApplicationContext<Context>()
            .deleteDatabase(PERFORMANCE_DB)
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
            listOf("single", "punctuated", "continuous"),
            database.messageDao()
                .searchSingleCjk(null, "你", 100)
                .map { it.messageId },
        )
    }

    @Test
    fun english_search_is_case_and_punctuation_insensitive() {
        database.messageDao().upsert(message("english", "Hello, Android world!", 1))

        val plan = LocalSearchText.plan("  HELLO android  ")!!
        assertEquals(
            listOf("english"),
            database.messageDao()
                .searchIndexed(null, plan.ftsMatch, plan.normalizedQuery, 100)
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
        performanceDatabase = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AccountDatabase::class.java,
            PERFORMANCE_DB,
        )
            .allowMainThreadQueries()
            .build()
        val benchmarkDatabase = performanceDatabase!!
        benchmarkDatabase.conversationDao().upsert(
            LocalConversationEntity(
                conversationId = CONVERSATION_ID,
                peerUserId = "user-2",
                peerAccountNo = "22345678902",
                peerDisplayName = "Bob",
                status = "ACTIVE",
                relationship = "ACTIVE",
                lastSequence = 100_000,
                updatedAt = 1,
            ),
        )
        val messageInsert = benchmarkDatabase.openHelper.writableDatabase.compileStatement(
            """
            INSERT INTO local_message (
                messageId, conversationId, senderId, senderDisplayName,
                clientMsgId, conversationSeq, type, state, localState, text,
                searchText, mediaId, localMediaPath, serverAcceptedAt, recalledAt,
                systemEventType, systemTargetUserId, systemRole, moderatedByUserId,
                moderatedReason, moderatedAt, createdAt
            ) VALUES (?, ?, ?, '', ?, ?, 'TEXT', 'ACTIVE', 'RECEIVED', ?,
                       ?, NULL, NULL, ?, NULL, NULL, NULL, NULL, NULL, NULL, NULL, ?)
            """.trimIndent(),
        )
        val searchInsert = benchmarkDatabase.openHelper.writableDatabase.compileStatement(
            "INSERT INTO local_message_search (messageId, terms) VALUES (?, ?)",
        )
        benchmarkDatabase.runInTransaction {
            (1L..100_000L).forEach { sequence ->
                bindPerformanceMessage(messageInsert, sequence)
                messageInsert.executeInsert()
                searchInsert.clearBindings()
                searchInsert.bindString(1, "performance-$sequence")
                searchInsert.bindString(2, "common keyword message $sequence")
                searchInsert.executeInsert()
            }
        }
        messageInsert.close()
        searchInsert.close()

        val plan = LocalSearchText.plan("common")!!
        repeat(5) {
            benchmarkDatabase.messageDao().searchIndexed(
                null,
                plan.ftsMatch,
                plan.normalizedQuery,
                100,
            )
        }
        val samples = List(20) {
            val started = System.nanoTime()
            benchmarkDatabase.messageDao().searchIndexed(
                null,
                plan.ftsMatch,
                plan.normalizedQuery,
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

    private fun bindPerformanceMessage(statement: SupportSQLiteStatement, sequence: Long) {
        val id = "performance-$sequence"
        val text = "common keyword message $sequence"
        statement.clearBindings()
        statement.bindString(1, id)
        statement.bindString(2, CONVERSATION_ID)
        statement.bindString(3, "user-1")
        statement.bindString(4, "client-$id")
        statement.bindLong(5, sequence)
        statement.bindString(6, text)
        statement.bindString(7, text)
        statement.bindString(8, "2026-08-21T00:00:00Z")
        statement.bindLong(9, sequence)
    }

    private companion object {
        const val CONVERSATION_ID = "conversation-1"
        const val PERFORMANCE_DB = "account-database-search-performance"
    }
}
