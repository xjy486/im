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
    val serverAcceptedAt: String?,
    val createdAt: Long,
)

@Dao
interface LocalConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(conversation: LocalConversationEntity)
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
}

@Database(
    entities = [
        LocalAccountEntity::class,
        LocalConversationEntity::class,
        LocalMessageEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AccountDatabase : RoomDatabase() {
    abstract fun accountDao(): LocalAccountDao
    abstract fun conversationDao(): LocalConversationDao
    abstract fun messageDao(): LocalMessageDao

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
    }
}
