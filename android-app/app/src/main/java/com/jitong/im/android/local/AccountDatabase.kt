package com.jitong.im.android.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.Query
import androidx.room.Index
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import com.jitong.im.search.LocalSearchText

@Entity(tableName = "local_account")
data class LocalAccountEntity(
    @PrimaryKey val userId: String,
    val accountNo: String,
    val deviceId: String,
    val displayName: String?,
    val avatarUrl: String? = null,
    val avatarVersion: Long = 0,
    val avatarFallback: String = "?",
)

@Dao
interface LocalAccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(account: LocalAccountEntity)

    @Query("SELECT * FROM local_account LIMIT 1")
    fun current(): LocalAccountEntity?
}

@Entity(tableName = "local_conversation")
data class LocalConversationEntity(
    @PrimaryKey val conversationId: String,
    val peerUserId: String,
    val peerAccountNo: String?,
    val peerDisplayName: String,
    val peerAvatarUrl: String? = null,
    val peerAvatarVersion: Long = 0,
    val peerAvatarFallback: String = "?",
    val status: String,
    val relationship: String,
    val lastSequence: Long,
    val updatedAt: Long,
    val searchVisible: Boolean = true,
    val searchVisibleAfterSeq: Long = 0,
)

@Entity(
    tableName = "local_message",
    indices = [
        Index(value = ["conversationId", "conversationSeq"]),
        Index(value = ["clientMsgId"]),
    ],
)
data class LocalMessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderId: String,
    val senderDisplayName: String = "",
    val clientMsgId: String,
    val conversationSeq: Long?,
    val type: String,
    val state: String,
    val localState: String,
    val text: String,
    val searchText: String = "",
    val mediaId: String? = null,
    val localMediaPath: String? = null,
    val serverAcceptedAt: String?,
    val recalledAt: String? = null,
    val systemEventType: String? = null,
    val systemTargetUserId: String? = null,
    val systemRole: String? = null,
    val moderatedByUserId: String? = null,
    val moderatedReason: String? = null,
    val moderatedAt: String? = null,
    val createdAt: Long,
)

@Fts4(
    tokenizer = "simple",
)
@Entity(tableName = "local_message_search")
data class LocalMessageSearchEntity(
    val messageId: String,
    val terms: String,
)

@Entity(tableName = "local_search_state")
data class LocalSearchStateEntity(
    @PrimaryKey val id: Int = 1,
    val version: Int,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val deviceId: String,
    val lastSyncSeq: Long,
    val lastFullRestoreAt: Long?,
)

@Entity(
    tableName = "local_conversation_read_state",
    primaryKeys = ["conversationId", "userId"],
)
data class LocalConversationReadStateEntity(
    val conversationId: String,
    val userId: String,
    val readSeq: Long,
)

@Entity(
    tableName = "pending_commands",
    indices = [
        Index(value = ["status", "createdAt"]),
    ],
)
data class PendingMessageCommandEntity(
    @PrimaryKey val clientMsgId: String,
    val conversationId: String,
    val text: String,
    val createdAt: Long,
    val status: String,
    val type: String = "TEXT",
    val mediaId: String? = null,
    val uploadId: String? = null,
    val mediaPath: String? = null,
)

@Entity(tableName = "local_group_profile")
data class LocalGroupProfileEntity(
    @PrimaryKey val conversationId: String,
    val avatarUrl: String?,
    val avatarVersion: Long,
)

@Entity(
    tableName = "local_ai_artifact",
    indices = [Index(value = ["jobId"])],
)
data class LocalAiArtifactEntity(
    @PrimaryKey val artifactId: String,
    val jobId: String,
    val conversationId: String,
    val artifactType: String,
    val contentJson: String,
    val createdAt: String,
    val expiresAt: String,
)

@Dao
interface LocalAiArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(artifacts: List<LocalAiArtifactEntity>)

    @Query("SELECT * FROM local_ai_artifact WHERE expiresAt > :now ORDER BY createdAt DESC, artifactId")
    fun listActive(now: String): List<LocalAiArtifactEntity>

    @Query("DELETE FROM local_ai_artifact WHERE artifactId = :artifactId")
    fun delete(artifactId: String)

    @Query("DELETE FROM local_ai_artifact WHERE jobId = :jobId")
    fun deleteForJob(jobId: String)

    @Query("DELETE FROM local_ai_artifact")
    fun clearAll()

    @Query("DELETE FROM local_ai_artifact WHERE expiresAt <= :now")
    fun deleteExpired(now: String)
}

@Entity(
    tableName = "local_ai_action_item",
    indices = [Index(value = ["conversationId"]), Index(value = ["status"])],
)
data class LocalAiActionItemEntity(
    @PrimaryKey val actionItemId: String,
    val sourceJobId: String?,
    val ownerUserId: String,
    val conversationId: String,
    val assigneeUserId: String?,
    val title: String,
    val details: String,
    val dueAt: String?,
    val priority: String,
    val confidence: Double,
    val sourceMessageIdsJson: String,
    val status: String,
    val createdAt: String,
    val completedAt: String?,
)

@Dao
interface LocalAiActionItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<LocalAiActionItemEntity>)

    @Query("SELECT * FROM local_ai_action_item ORDER BY status, createdAt DESC, actionItemId")
    fun listAll(): List<LocalAiActionItemEntity>

    @Query("SELECT * FROM local_ai_action_item WHERE conversationId = :conversationId ORDER BY status, createdAt DESC")
    fun listForConversation(conversationId: String): List<LocalAiActionItemEntity>

    @Query("DELETE FROM local_ai_action_item WHERE actionItemId = :actionItemId")
    fun delete(actionItemId: String)

    @Query("DELETE FROM local_ai_action_item")
    fun clearAll()
}

@Dao
interface LocalConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(conversation: LocalConversationEntity)

    @Query("SELECT * FROM local_conversation WHERE conversationId = :conversationId LIMIT 1")
    fun find(conversationId: String): LocalConversationEntity?

    @Query(
        """
        UPDATE local_conversation
        SET peerDisplayName = :displayName,
            peerAvatarUrl = :avatarUrl,
            peerAvatarVersion = :avatarVersion,
            peerAvatarFallback = :avatarFallback,
            updatedAt = :updatedAt
        WHERE peerUserId = :userId
        """,
    )
    fun updatePeerProfile(
        userId: String,
        displayName: String,
        avatarUrl: String?,
        avatarVersion: Long,
        avatarFallback: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE local_conversation
        SET searchVisible = :visible,
            searchVisibleAfterSeq = :visibleAfterSeq,
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId
        """,
    )
    fun updateSearchVisibility(
        conversationId: String,
        visible: Boolean,
        visibleAfterSeq: Long,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE local_conversation
        SET status = :status,
            relationship = :relationship,
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId
        """,
    )
    fun updateRelationship(
        conversationId: String,
        status: String,
        relationship: String,
        updatedAt: Long,
    )
}

@Dao
interface LocalGroupProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(profile: LocalGroupProfileEntity)

    @Query("SELECT * FROM local_group_profile WHERE conversationId = :conversationId LIMIT 1")
    fun find(conversationId: String): LocalGroupProfileEntity?

    @Query("DELETE FROM local_group_profile WHERE conversationId = :conversationId")
    fun delete(conversationId: String)

    @Query("DELETE FROM local_group_profile")
    fun clearAll()
}

@Dao
interface LocalConversationReadStateDao {
    @Query(
        """
        INSERT INTO local_conversation_read_state (conversationId, userId, readSeq)
        VALUES (:conversationId, :userId, :readSeq)
        ON CONFLICT(conversationId, userId)
        DO UPDATE SET readSeq = MAX(local_conversation_read_state.readSeq, excluded.readSeq)
        """,
    )
    fun advance(conversationId: String, userId: String, readSeq: Long)

    @Query(
        "SELECT * FROM local_conversation_read_state " +
            "WHERE conversationId = :conversationId AND userId = :userId LIMIT 1",
    )
    fun find(conversationId: String, userId: String): LocalConversationReadStateEntity?

    @Query("DELETE FROM local_conversation_read_state")
    fun clearAll()
}

@Dao
interface LocalMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertEntity(message: LocalMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSearchEntity(search: LocalMessageSearchEntity)

    @Query("DELETE FROM local_message_search WHERE messageId = :messageId")
    fun deleteSearchEntity(messageId: String)

    @Query("DELETE FROM local_message_search")
    fun clearSearchEntities()

    @Query("DELETE FROM local_message WHERE clientMsgId = :clientMsgId")
    fun deleteMessageEntityByClientMsgId(clientMsgId: String)

    @Query("DELETE FROM local_message WHERE localState IN ('SENT', 'RECEIVED')")
    fun clearAcceptedMessageEntities()

    @Query(
        "UPDATE local_message SET senderDisplayName = :displayName WHERE senderId = :userId",
    )
    fun updateMessageSenderDisplayName(userId: String, displayName: String)

    @Transaction
    fun upsert(message: LocalMessageEntity) {
        val indexedMessage = message.copy(
            searchText = if (message.type == "TEXT") {
                LocalSearchText.normalize(message.text)
            } else {
                ""
            },
        )
        upsertEntity(indexedMessage)
        deleteSearchEntity(message.messageId)
        if (
            indexedMessage.type == "TEXT" &&
            indexedMessage.state == "ACTIVE" &&
            indexedMessage.localState in setOf("SENT", "RECEIVED") &&
            indexedMessage.text.isNotBlank()
        ) {
            val terms = LocalSearchText.terms(indexedMessage.text)
            if (terms.isNotEmpty()) {
                insertSearchEntity(
                    LocalMessageSearchEntity(
                        messageId = message.messageId,
                        terms = terms.joinToString(" "),
                    ),
                )
            }
        }
    }

    @Query(
        """
        SELECT * FROM local_message
        WHERE conversationId = :conversationId
        ORDER BY
            CASE WHEN conversationSeq IS NULL THEN 1 ELSE 0 END,
            conversationSeq ASC,
            messageId ASC
        """,
    )
    fun observe(conversationId: String): Flow<List<LocalMessageEntity>>

    @Query("SELECT * FROM local_message WHERE clientMsgId = :clientMsgId LIMIT 1")
    fun findByClientMsgId(clientMsgId: String): LocalMessageEntity?

    @Transaction
    fun deleteByClientMsgId(clientMsgId: String) {
        findByClientMsgId(clientMsgId)?.let { deleteSearchEntity(it.messageId) }
        deleteMessageEntityByClientMsgId(clientMsgId)
    }

    @Transaction
    fun clearAll() {
        clearSearchEntities()
        clearMessageEntities()
    }

    @Transaction
    fun clearAccepted() {
        clearSearchEntities()
        clearAcceptedMessageEntities()
        rebuildSearchEntities()
    }

    @Query("DELETE FROM local_message")
    fun clearMessageEntities()

    @Query(
        """
        SELECT mediaId FROM local_message
        WHERE conversationId = :conversationId
          AND localState IN ('SENT', 'RECEIVED')
          AND type = 'IMAGE'
          AND mediaId IS NOT NULL
        """,
    )
    fun acceptedImageMediaIds(conversationId: String): List<String>

    @Query(
        """
        SELECT mediaId FROM local_message
        WHERE conversationId = :conversationId
          AND type = 'IMAGE'
          AND mediaId IS NOT NULL
        """,
    )
    fun imageMediaIds(conversationId: String): List<String>

    @Query(
        """
        SELECT localMediaPath FROM local_message
        WHERE conversationId = :conversationId
          AND type = 'IMAGE'
          AND localMediaPath IS NOT NULL
        """,
    )
    fun imageCachePaths(conversationId: String): List<String>

    @Query("DELETE FROM local_message_search WHERE messageId IN (SELECT messageId FROM local_message WHERE conversationId = :conversationId)")
    fun deleteSearchForConversation(conversationId: String)

    @Query("DELETE FROM local_message WHERE conversationId = :conversationId")
    fun deleteConversationMessages(conversationId: String)

    @Query(
        """
        SELECT COALESCE(MAX(conversationSeq), 0)
        FROM local_message
        WHERE conversationId = :conversationId
        """,
    )
    fun lastConversationSeq(conversationId: String): Long

    @Transaction
    fun clearConversation(conversationId: String) {
        deleteSearchForConversation(conversationId)
        deleteConversationMessages(conversationId)
    }

    @Query(
        """
        SELECT m.*
        FROM local_message AS m
        JOIN local_message_search AS search ON search.messageId = m.messageId
        JOIN local_conversation AS conversation ON conversation.conversationId = m.conversationId
        WHERE (:conversationId IS NULL OR m.conversationId = :conversationId)
          AND conversation.searchVisible = 1
          AND (m.conversationSeq IS NULL OR m.conversationSeq > conversation.searchVisibleAfterSeq)
          AND m.state = 'ACTIVE'
          AND m.localState IN ('SENT', 'RECEIVED')
          AND local_message_search MATCH :match
          AND instr(m.searchText, :query) > 0
        ORDER BY m.serverAcceptedAt DESC, m.createdAt DESC, m.messageId DESC
        LIMIT :limit
        """,
    )
    fun searchIndexed(
        conversationId: String?,
        match: String,
        query: String,
        limit: Int,
    ): List<LocalMessageEntity>

    @Query(
        """
        SELECT local_message.*
        FROM local_message
        JOIN local_conversation AS conversation
          ON conversation.conversationId = local_message.conversationId
        WHERE (:conversationId IS NULL OR local_message.conversationId = :conversationId)
          AND conversation.searchVisible = 1
          AND (conversationSeq IS NULL OR conversationSeq > conversation.searchVisibleAfterSeq)
          AND state = 'ACTIVE'
          AND localState IN ('SENT', 'RECEIVED')
          AND searchText LIKE '%' || :query || '%'
        ORDER BY CASE WHEN conversationSeq IS NULL THEN 1 ELSE 0 END,
                 conversationSeq DESC,
                 createdAt DESC
        LIMIT :limit
        """,
    )
    fun searchSingleCjk(
        conversationId: String?,
        query: String,
        limit: Int,
    ): List<LocalMessageEntity>

    @Transaction
    fun rebuildSearchEntities() {
        clearSearchEntities()
        listAll().forEach { upsert(it) }
    }

    @Query("SELECT * FROM local_message")
    fun listAll(): List<LocalMessageEntity>

    @Query(
        "SELECT mediaId FROM local_message " +
            "WHERE localState IN ('SENT', 'RECEIVED') " +
            "AND type = 'IMAGE' AND mediaId IS NOT NULL",
    )
    fun acceptedImageMediaIds(): List<String>

    @Query("UPDATE local_message SET localState = :localState WHERE clientMsgId = :clientMsgId")
    fun updateLocalState(clientMsgId: String, localState: String)

    @Query("UPDATE local_message SET localMediaPath = :localMediaPath WHERE messageId = :messageId")
    fun updateLocalMediaPath(messageId: String, localMediaPath: String)

}

@Dao
interface LocalSearchStateDao {
    @Query("SELECT * FROM local_search_state WHERE id = 1")
    fun current(): LocalSearchStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(state: LocalSearchStateEntity)
}

@Dao
interface PendingCommandDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(command: PendingMessageCommandEntity)

    @Query(
        "SELECT * FROM pending_commands " +
            "WHERE status IN ('PENDING', 'SENDING') ORDER BY createdAt ASC",
    )
    fun pending(): List<PendingMessageCommandEntity>

    @Query("UPDATE pending_commands SET status = 'PENDING' WHERE status = 'SENDING'")
    fun resetInFlight()

    @Query(
        "UPDATE pending_commands SET status = 'SENDING' " +
            "WHERE clientMsgId = :clientMsgId AND status = 'PENDING'",
    )
    fun markSending(clientMsgId: String)

    @Query(
        "UPDATE pending_commands SET status = 'PENDING' " +
            "WHERE clientMsgId = :clientMsgId AND status = 'SENDING'",
    )
    fun markPending(clientMsgId: String): Int

    @Query(
        "UPDATE pending_commands SET status = 'MANUAL_RETRY' " +
            "WHERE status IN ('PENDING', 'SENDING')",
    )
    fun markManualRetry()

    @Query(
        "UPDATE pending_commands SET status = 'MANUAL_RETRY' " +
            "WHERE clientMsgId = :clientMsgId AND status IN ('PENDING', 'SENDING')",
    )
    fun markCommandManualRetry(clientMsgId: String)

    @Query(
        "UPDATE pending_commands SET status = 'PENDING' " +
            "WHERE clientMsgId = :clientMsgId AND status = 'MANUAL_RETRY'",
    )
    fun markForRetry(clientMsgId: String)

    @Query("DELETE FROM pending_commands WHERE clientMsgId = :clientMsgId")
    fun delete(clientMsgId: String)

    @Query("DELETE FROM pending_commands WHERE conversationId = :conversationId")
    fun deleteForConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM pending_commands WHERE status IN ('PENDING', 'SENDING')")
    fun pendingCount(): Int
}

@Dao
interface SyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun current(): SyncStateEntity?
}

@Database(
    entities = [
        LocalAccountEntity::class,
        LocalConversationEntity::class,
        LocalMessageEntity::class,
        LocalMessageSearchEntity::class,
        LocalSearchStateEntity::class,
        SyncStateEntity::class,
        LocalConversationReadStateEntity::class,
        PendingMessageCommandEntity::class,
        LocalGroupProfileEntity::class,
        LocalAiArtifactEntity::class,
        LocalAiActionItemEntity::class,
    ],
    version = 18,
    exportSchema = true,
)
abstract class AccountDatabase : RoomDatabase() {
    abstract fun accountDao(): LocalAccountDao
    abstract fun conversationDao(): LocalConversationDao
    abstract fun conversationReadStateDao(): LocalConversationReadStateDao
    abstract fun messageDao(): LocalMessageDao
    abstract fun searchStateDao(): LocalSearchStateDao
    abstract fun pendingCommandDao(): PendingCommandDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun groupProfileDao(): LocalGroupProfileDao
    abstract fun aiArtifactDao(): LocalAiArtifactDao
    abstract fun aiActionItemDao(): LocalAiActionItemDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(
                database: androidx.sqlite.db.SupportSQLiteDatabase,
            ) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_conversation (
                        conversationId TEXT NOT NULL PRIMARY KEY,
                        peerUserId TEXT NOT NULL,
                        peerAccountNo TEXT NOT NULL,
                        peerDisplayName TEXT NOT NULL,
                        status TEXT NOT NULL,
                        relationship TEXT NOT NULL,
                        lastSequence INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_message (
                        messageId TEXT NOT NULL PRIMARY KEY,
                        conversationId TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        clientMsgId TEXT NOT NULL,
                        conversationSeq INTEGER,
                        type TEXT NOT NULL,
                        state TEXT NOT NULL,
                        localState TEXT NOT NULL,
                        text TEXT NOT NULL,
                        serverAcceptedAt TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_message_conversationId_conversationSeq " +
                        "ON local_message (conversationId, conversationSeq)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_message_clientMsgId " +
                        "ON local_message (clientMsgId)",
                )
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(
                database: androidx.sqlite.db.SupportSQLiteDatabase,
            ) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        deviceId TEXT NOT NULL DEFAULT '',
                        lastSyncSeq INTEGER NOT NULL,
                        lastFullRestoreAt INTEGER
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "INSERT OR IGNORE INTO sync_state (id, deviceId, lastSyncSeq, lastFullRestoreAt) " +
                        "VALUES (1, '', 0, NULL)",
                )
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(
                database: androidx.sqlite.db.SupportSQLiteDatabase,
            ) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_conversation_read_state (
                        conversationId TEXT NOT NULL,
                        userId TEXT NOT NULL,
                        readSeq INTEGER NOT NULL,
                        PRIMARY KEY(conversationId, userId)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(
                database: androidx.sqlite.db.SupportSQLiteDatabase,
            ) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_commands (
                        clientMsgId TEXT NOT NULL PRIMARY KEY,
                        conversationId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        status TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pending_commands_status_createdAt " +
                        "ON pending_commands (status, createdAt)",
                )
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(
                database: androidx.sqlite.db.SupportSQLiteDatabase,
            ) {
                database.execSQL("ALTER TABLE local_message ADD COLUMN mediaId TEXT")
                database.execSQL("ALTER TABLE local_message ADD COLUMN localMediaPath TEXT")
                database.execSQL("ALTER TABLE pending_commands ADD COLUMN type TEXT NOT NULL DEFAULT 'TEXT'")
                database.execSQL("ALTER TABLE pending_commands ADD COLUMN mediaId TEXT")
                database.execSQL("ALTER TABLE pending_commands ADD COLUMN uploadId TEXT")
                database.execSQL("ALTER TABLE pending_commands ADD COLUMN mediaPath TEXT")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(
                database: androidx.sqlite.db.SupportSQLiteDatabase,
            ) {
                database.execSQL("ALTER TABLE local_account ADD COLUMN avatarUrl TEXT")
                database.execSQL(
                    "ALTER TABLE local_account ADD COLUMN avatarVersion INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL("ALTER TABLE local_conversation ADD COLUMN peerAvatarUrl TEXT")
                database.execSQL(
                    "ALTER TABLE local_conversation ADD COLUMN peerAvatarVersion INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE local_message ADD COLUMN recalledAt TEXT")
            }
        }

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS local_message_search
                    USING FTS4(`messageId` TEXT NOT NULL, `terms` TEXT NOT NULL, notindexed=`messageId`)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE local_message ADD COLUMN searchText TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_search_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        version INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE local_conversation ADD COLUMN searchVisible INTEGER NOT NULL DEFAULT 1",
                )
                database.execSQL(
                    "ALTER TABLE local_conversation ADD COLUMN searchVisibleAfterSeq INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS local_message_search_v14")
                database.execSQL(
                    """
                    CREATE VIRTUAL TABLE local_message_search_v14
                    USING FTS4(messageId TEXT NOT NULL, terms TEXT NOT NULL)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO local_message_search_v14 (messageId, terms)
                    SELECT messageId, terms
                    FROM local_message_search
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE local_message_search")
                database.execSQL(
                    "ALTER TABLE local_message_search_v14 RENAME TO local_message_search",
                )
            }
        }

        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE local_message ADD COLUMN systemEventType TEXT")
                database.execSQL("ALTER TABLE local_message ADD COLUMN systemTargetUserId TEXT")
                database.execSQL("ALTER TABLE local_message ADD COLUMN systemRole TEXT")
                database.execSQL("ALTER TABLE local_message ADD COLUMN moderatedByUserId TEXT")
                database.execSQL("ALTER TABLE local_message ADD COLUMN moderatedReason TEXT")
                database.execSQL("ALTER TABLE local_message ADD COLUMN moderatedAt TEXT")
            }
        }

        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_ai_artifact (
                        artifactId TEXT NOT NULL PRIMARY KEY,
                        jobId TEXT NOT NULL,
                        conversationId TEXT NOT NULL,
                        artifactType TEXT NOT NULL,
                        contentJson TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        expiresAt TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_ai_artifact_jobId " +
                        "ON local_ai_artifact (jobId)",
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_ai_action_item (
                        actionItemId TEXT NOT NULL PRIMARY KEY,
                        sourceJobId TEXT,
                        ownerUserId TEXT NOT NULL,
                        conversationId TEXT NOT NULL,
                        assigneeUserId TEXT,
                        title TEXT NOT NULL,
                        details TEXT NOT NULL,
                        dueAt TEXT,
                        priority TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        sourceMessageIdsJson TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        completedAt TEXT
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_ai_action_item_conversationId " +
                        "ON local_ai_action_item (conversationId)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_ai_action_item_status " +
                        "ON local_ai_action_item (status)",
                )
            }
        }

        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE local_message ADD COLUMN senderDisplayName TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE local_conversation RENAME TO local_conversation_v17")
                database.execSQL(
                    """
                    CREATE TABLE local_conversation (
                        conversationId TEXT NOT NULL PRIMARY KEY,
                        peerUserId TEXT NOT NULL,
                        peerAccountNo TEXT,
                        peerDisplayName TEXT NOT NULL,
                        peerAvatarUrl TEXT,
                        peerAvatarVersion INTEGER NOT NULL,
                        peerAvatarFallback TEXT NOT NULL,
                        status TEXT NOT NULL,
                        relationship TEXT NOT NULL,
                        lastSequence INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        searchVisible INTEGER NOT NULL,
                        searchVisibleAfterSeq INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO local_conversation (
                        conversationId, peerUserId, peerAccountNo, peerDisplayName,
                        peerAvatarUrl, peerAvatarVersion, peerAvatarFallback,
                        status, relationship, lastSequence, updatedAt,
                        searchVisible, searchVisibleAfterSeq
                    )
                    SELECT conversationId, peerUserId, peerAccountNo, peerDisplayName,
                           peerAvatarUrl, peerAvatarVersion, peerAvatarFallback,
                           status, relationship, lastSequence, updatedAt,
                           searchVisible, searchVisibleAfterSeq
                    FROM local_conversation_v17
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE local_conversation_v17")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(
                database: androidx.sqlite.db.SupportSQLiteDatabase,
            ) {
                database.execSQL(
                    "ALTER TABLE local_account ADD COLUMN avatarFallback TEXT NOT NULL DEFAULT '?'",
                )
                database.execSQL(
                    "ALTER TABLE local_conversation ADD COLUMN peerAvatarFallback TEXT NOT NULL DEFAULT '?'",
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_group_profile (
                        conversationId TEXT NOT NULL PRIMARY KEY,
                        avatarUrl TEXT,
                        avatarVersion INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
