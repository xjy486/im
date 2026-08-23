package com.jitong.im.android.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jitong.im.search.LocalSearchText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AccountDatabase::class.java,
    )

    private var migratedDatabase: AccountDatabase? = null

    @After
    fun tearDown() {
        migratedDatabase?.close()
        ApplicationProvider.getApplicationContext<Context>()
            .deleteDatabase(TEST_DB)
    }

    @Test
    fun migrates_v9_search_schema_through_v16_and_rebuilds_legacy_messages() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL(
                """
                INSERT INTO local_conversation (
                    conversationId, peerUserId, peerAccountNo, peerDisplayName,
                    peerAvatarUrl, peerAvatarVersion, peerAvatarFallback, status,
                    relationship, lastSequence, updatedAt
                ) VALUES (
                    'conversation-1', 'user-2', '22345678902', 'Bob',
                    NULL, 0, '?', 'ACTIVE', 'ACTIVE', 1, 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO local_message (
                    messageId, conversationId, senderId, clientMsgId,
                    conversationSeq, type, state, localState, text,
                    mediaId, localMediaPath, serverAcceptedAt, recalledAt, createdAt
                ) VALUES (
                    'message-1', 'conversation-1', 'user-1', 'client-1',
                    1, 'TEXT', 'ACTIVE', 'RECEIVED', 'Legacy Hello',
                    NULL, NULL, '2026-08-21T00:00:00Z', NULL, 1
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            16,
            true,
            AccountDatabase.MIGRATION_9_10,
            AccountDatabase.MIGRATION_10_11,
            AccountDatabase.MIGRATION_11_12,
            AccountDatabase.MIGRATION_12_13,
            AccountDatabase.MIGRATION_13_14,
            AccountDatabase.MIGRATION_14_15,
            AccountDatabase.MIGRATION_15_16,
        ).close()

        migratedDatabase = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AccountDatabase::class.java,
            TEST_DB,
        )
            .addMigrations(
                AccountDatabase.MIGRATION_9_10,
                AccountDatabase.MIGRATION_10_11,
                AccountDatabase.MIGRATION_11_12,
                AccountDatabase.MIGRATION_12_13,
                AccountDatabase.MIGRATION_13_14,
                AccountDatabase.MIGRATION_14_15,
                AccountDatabase.MIGRATION_15_16,
            )
            .build()

        migratedDatabase!!.messageDao().rebuildSearchEntities()
        val plan = LocalSearchText.plan("hello")!!
        assertEquals(
            listOf("message-1"),
            migratedDatabase!!.messageDao()
                .searchIndexed(null, plan.ftsMatch, plan.normalizedQuery, 100)
                .map { it.messageId },
        )
        assertEquals(
            1,
            migratedDatabase!!.openHelper.writableDatabase
                .query("SELECT searchVisible, searchVisibleAfterSeq FROM local_conversation")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                    assertEquals(0, cursor.getLong(1))
                    1
                },
        )
        assertEquals(
            0,
            migratedDatabase!!.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM local_ai_artifact")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getInt(0)
                },
        )
        assertEquals(
            0,
            migratedDatabase!!.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM local_ai_action_item")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getInt(0)
                },
        )
    }

    private companion object {
        const val TEST_DB = "account-database-migration-test"
    }
}
