package com.jitong.im.desktop.local

import org.h2.jdbcx.JdbcConnectionPool
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists

class LocalDatabaseManager(
    private val rootDirectory: Path,
    private val keychain: Keychain,
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        private const val KEYCHAIN_SERVICE = "com.jitong.im.desktop.local-db"
        private const val KEY_LENGTH_BYTES = 32
    }

    init {
        rootDirectory.createDirectories()
    }

    fun open(accountNo: String): LocalDatabase {
        val normalizedAccount = normalizeAccountNo(accountNo)
        val keychainAccount = "account:$normalizedAccount"
        val password = keychain.read(KEYCHAIN_SERVICE, keychainAccount)
            ?: newDatabasePassword().also {
                keychain.write(KEYCHAIN_SERVICE, keychainAccount, it)
            }
        val databasePath = rootDirectory.resolve("account-$normalizedAccount")
        val jdbcUrl = "jdbc:h2:file:$databasePath;CIPHER=AES;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE"
        val pool = try {
            JdbcConnectionPool.create(jdbcUrl, "sa", "$password ")
        } catch (exception: RuntimeException) {
            // Do not leave a newly-generated Keychain item behind when the database cannot open.
            if (keychain.read(KEYCHAIN_SERVICE, keychainAccount) == password && !databasePath.resolveSibling(databasePath.fileName.toString() + ".mv.db").toFile().exists()) {
                keychain.delete(KEYCHAIN_SERVICE, keychainAccount)
            }
            throw exception
        }
        pool.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS local_metadata (
                        metadata_key VARCHAR(128) PRIMARY KEY,
                        metadata_value VARCHAR(2048) NOT NULL
                    )
                    """.trimIndent())
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS session_state (
                        id INT PRIMARY KEY,
                        account_no VARCHAR(11) NOT NULL,
                        user_id VARCHAR(36) NOT NULL,
                        device_id VARCHAR(36) NOT NULL,
                        device_class VARCHAR(16) NOT NULL,
                        access_token VARCHAR(512),
                        refresh_token VARCHAR(512),
                        access_token_expires_at VARCHAR(64),
                        refresh_token_expires_at VARCHAR(64)
                    )
                    """.trimIndent())
            }
        }
        return LocalDatabase(
            accountNo = normalizedAccount,
            databasePath = databasePath,
            pool = pool)
    }

    fun clear(accountNo: String) {
        val normalizedAccount = normalizeAccountNo(accountNo)
        val databasePath = rootDirectory.resolve("account-$normalizedAccount")
        val keychainAccount = "account:$normalizedAccount"
        keychain.delete(KEYCHAIN_SERVICE, keychainAccount)
        listOf(
            databasePath,
            Path.of("$databasePath.mv.db"),
            Path.of("$databasePath.lock.db"),
            Path.of("$databasePath.trace.db"),
        ).forEach { it.deleteIfExists() }
    }

    fun databaseFile(accountNo: String): Path = rootDirectory.resolve("account-${normalizeAccountNo(accountNo)}.mv.db")

    fun findAccounts(): List<String> = rootDirectory
        .toFile()
        .listFiles { file ->
            file.isFile && file.name.matches(Regex("account-[1-9][0-9]{10}\\.mv\\.db"))
        }
        ?.map { it.name.removePrefix("account-").removeSuffix(".mv.db") }
        ?.sorted()
        ?: emptyList()

    private fun newDatabasePassword(): String {
        val bytes = ByteArray(KEY_LENGTH_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun normalizeAccountNo(accountNo: String): String {
        require(accountNo.matches(Regex("[1-9][0-9]{10}"))) {
            "accountNo must be an 11-digit public account number"
        }
        return accountNo
    }
}

class LocalDatabase internal constructor(
    val accountNo: String,
    val databasePath: Path,
    private val pool: JdbcConnectionPool,
) : AutoCloseable {
    fun saveSession(session: StoredSession) {
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO session_state (id, account_no, user_id, device_id, device_class,
                    access_token, refresh_token, access_token_expires_at, refresh_token_expires_at)
                KEY(id) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
                statement.setString(1, session.accountNo)
                statement.setString(2, session.userId)
                statement.setString(3, session.deviceId)
                statement.setString(4, session.deviceClass)
                statement.setString(5, session.accessToken)
                statement.setString(6, session.refreshToken)
                statement.setString(7, session.accessTokenExpiresAt)
                statement.setString(8, session.refreshTokenExpiresAt)
                statement.executeUpdate()
            }
        }
    }

    fun loadSession(): StoredSession? {
        pool.connection.use { connection ->
            connection.prepareStatement(
                "SELECT account_no, user_id, device_id, device_class, access_token, refresh_token, " +
                    "access_token_expires_at, refresh_token_expires_at FROM session_state WHERE id = 1")
                .use { statement ->
                    statement.executeQuery().use { result ->
                        if (!result.next()) return null
                        return StoredSession(
                            accountNo = result.getString("account_no"),
                            userId = result.getString("user_id"),
                            deviceId = result.getString("device_id"),
                            deviceClass = result.getString("device_class"),
                            accessToken = result.getString("access_token"),
                            refreshToken = result.getString("refresh_token"),
                            accessTokenExpiresAt = result.getString("access_token_expires_at"),
                            refreshTokenExpiresAt = result.getString("refresh_token_expires_at"))
                    }
                }
        }
    }

    fun clearSession() {
        pool.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM session_state WHERE id = 1")
            }
        }
    }

    override fun close() {
        pool.dispose()
    }
}

data class StoredSession(
    val accountNo: String,
    val userId: String,
    val deviceId: String,
    val deviceClass: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: String,
    val refreshTokenExpiresAt: String,
)
