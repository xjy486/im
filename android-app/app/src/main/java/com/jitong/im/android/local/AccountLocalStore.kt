package com.jitong.im.android.local

import android.content.Context
import androidx.room.Room
import com.jitong.im.android.auth.LoginResponse
import com.jitong.im.android.auth.SessionSnapshot
import com.jitong.im.android.security.AccountKeyStore
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.io.File

/** Owns one SQLCipher database and media cache for the currently active account. */
class AccountLocalStore(
    private val context: Context,
    private val keyStore: AccountKeyStore,
) {
    private var openedAccountNo: String? = null
    private var openedDatabase: AccountDatabase? = null

    init {
        SQLiteDatabase.loadLibs(context.applicationContext)
    }

    @Synchronized
    fun ensureAccount(response: LoginResponse): AccountDatabase {
        return ensureAccount(
            userId = response.userId,
            accountNo = response.accountNo,
            deviceId = response.deviceId,
        )
    }

    @Synchronized
    fun ensureAccount(snapshot: SessionSnapshot): AccountDatabase {
        return ensureAccount(
            userId = snapshot.userId,
            accountNo = snapshot.accountNo,
            deviceId = snapshot.deviceId,
        )
    }

    @Synchronized
    fun closeActive() {
        openedDatabase?.close()
        openedDatabase = null
        openedAccountNo = null
    }

    @Synchronized
    fun activeDatabase(): AccountDatabase? = openedDatabase

    @Synchronized
    fun markPendingCommandsForManualRetry() {
        val database = openedDatabase ?: return
        database.runInTransaction {
            val pending = database.pendingCommandDao().pending()
            database.pendingCommandDao().markManualRetry()
            pending.forEach { command ->
                database.messageDao().updateLocalState(
                    command.clientMsgId,
                    "MANUAL_RETRY",
                )
            }
        }
    }

    private fun ensureAccount(
        userId: String,
        accountNo: String,
        deviceId: String,
    ): AccountDatabase {
        if (openedAccountNo != accountNo) {
            closeActive()
        }

        val database = openedDatabase ?: open(accountNo).also {
            openedDatabase = it
            openedAccountNo = accountNo
        }
        database.accountDao().upsert(
            LocalAccountEntity(
                userId = userId,
                accountNo = accountNo,
                deviceId = deviceId,
                displayName = null,
            ),
        )
        return database
    }

    @Synchronized
    fun forgetAccount(accountNo: String) {
        if (openedAccountNo == accountNo) {
            closeActive()
        }
        context.deleteDatabase(keyStore.databaseName(accountNo))
        File(context.filesDir, keyStore.mediaDirectoryName(accountNo)).deleteRecursively()
        keyStore.forget(accountNo)
    }

    private fun open(accountNo: String): AccountDatabase {
        val passphrase = keyStore.databasePassphrase(accountNo)
        val factory = SupportFactory(SQLiteDatabase.getBytes(passphrase.toCharArray()))
        return Room.databaseBuilder(
            context,
            AccountDatabase::class.java,
            keyStore.databaseName(accountNo),
        )
            .openHelperFactory(factory)
            .addMigrations(AccountDatabase.MIGRATION_1_2)
            .addMigrations(AccountDatabase.MIGRATION_2_3)
            .addMigrations(AccountDatabase.MIGRATION_3_4)
            .addMigrations(AccountDatabase.MIGRATION_4_5)
            .build()
    }
}
