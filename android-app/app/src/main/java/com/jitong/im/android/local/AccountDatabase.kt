package com.jitong.im.android.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.Query
import androidx.room.Index
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_account")
data class LocalAccountEntity(
    @PrimaryKey val userId: String,
    val accountNo: String,
    val deviceId: String,
    val displayName: String?,
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
    val peerAccountNo: String,
    val peerDisplayName: String,
    val status: String,
    val relationship: String,
    val lastSequence: Long,
    val updatedAt: Long,
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
    val clientMsgId: String,
    val conversationSeq: Long?,
    val type: String,
    val state: String,
    val localState: String,
    val text: String,
    val mediaId: String? = null,
    val localMediaPath: String? = null,
    val serverAcceptedAt: String?,
    val createdAt: Long,
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

@Dao
interface LocalConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(conversation: LocalConversationEntity)
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
    fun upsert(message: LocalMessageEntity)

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

    @Query("DELETE FROM local_message WHERE clientMsgId = :clientMsgId")
    fun deleteByClientMsgId(clientMsgId: String)

    @Query("DELETE FROM local_message")
    fun clearAll()

    @Query("DELETE FROM local_message WHERE localState IN ('SENT', 'RECEIVED')")
    fun clearAccepted()

    @Query("UPDATE local_message SET localState = :localState WHERE clientMsgId = :clientMsgId")
    fun updateLocalState(clientMsgId: String, localState: String)

    @Query("UPDATE local_message SET localMediaPath = :localMediaPath WHERE messageId = :messageId")
    fun updateLocalMediaPath(messageId: String, localMediaPath: String)
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
        SyncStateEntity::class,
        LocalConversationReadStateEntity::class,
        PendingMessageCommandEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AccountDatabase : RoomDatabase() {
    abstract fun accountDao(): LocalAccountDao
    abstract fun conversationDao(): LocalConversationDao
    abstract fun conversationReadStateDao(): LocalConversationReadStateDao
    abstract fun messageDao(): LocalMessageDao
    abstract fun pendingCommandDao(): PendingCommandDao
    abstract fun syncStateDao(): SyncStateDao

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
    }
}
