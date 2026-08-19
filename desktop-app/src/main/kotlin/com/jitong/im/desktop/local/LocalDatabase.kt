package com.jitong.im.desktop.local

import com.jitong.im.desktop.auth.DeviceClass
import org.h2.jdbcx.JdbcConnectionPool
import java.nio.file.Files
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
        private const val REFRESH_TOKEN_KEYCHAIN_SERVICE = "com.jitong.im.desktop.refresh-token"
        private const val KEY_LENGTH_BYTES = 32
    }

    init {
        rootDirectory.createDirectories()
    }

    fun open(accountNo: String): LocalDatabase {
        val normalizedAccount = normalizeAccountNo(accountNo)
        val keychainAccount = "account:$normalizedAccount"
        val existingPassword = keychain.read(KEYCHAIN_SERVICE, keychainAccount)
        val password = existingPassword
            ?: newDatabasePassword().also {
                keychain.write(KEYCHAIN_SERVICE, keychainAccount, it)
            }
        val databasePath = rootDirectory.resolve("account-$normalizedAccount")
        val jdbcUrl = "jdbc:h2:file:$databasePath;CIPHER=AES;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE"
        val pool = JdbcConnectionPool.create(jdbcUrl, "sa", "$password ")
        try {
            pool.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS session_state (
                            id INT PRIMARY KEY,
                            account_no VARCHAR(11) NOT NULL,
                            user_id VARCHAR(36) NOT NULL,
                            device_id VARCHAR(36) NOT NULL,
                            device_class VARCHAR(16) NOT NULL,
                            access_token_expires_at VARCHAR(64),
                            refresh_token_expires_at VARCHAR(64)
                        )
                        """.trimIndent())
                    // Remove token columns created by the first implementation. Tokens belong in Keychain
                    // or memory, never in the encrypted message database.
                    statement.executeUpdate(
                        "ALTER TABLE session_state DROP COLUMN IF EXISTS access_token")
                    statement.executeUpdate(
                        "ALTER TABLE session_state DROP COLUMN IF EXISTS refresh_token")
                }
            }
        } catch (exception: RuntimeException) {
            pool.dispose()
            // Do not leave a newly-generated Keychain item behind when initialization fails.
            if (existingPassword == null
                && !databaseFile(normalizedAccount).toFile().exists()) {
                keychain.delete(KEYCHAIN_SERVICE, keychainAccount)
            }
            throw exception
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
        clearRefreshToken(normalizedAccount)
        mediaCache(normalizedAccount).clear()
        listOf(
            databasePath,
            Path.of("$databasePath.mv.db"),
            Path.of("$databasePath.lock.db"),
            Path.of("$databasePath.trace.db"),
        ).forEach { it.deleteIfExists() }
    }

    fun databaseFile(accountNo: String): Path = rootDirectory.resolve("account-${normalizeAccountNo(accountNo)}.mv.db")

    fun saveRefreshToken(accountNo: String, refreshToken: String) {
        keychain.write(
            REFRESH_TOKEN_KEYCHAIN_SERVICE,
            refreshTokenKeychainAccount(accountNo),
            refreshToken)
    }

    fun loadRefreshToken(accountNo: String): String? {
        return keychain.read(
            REFRESH_TOKEN_KEYCHAIN_SERVICE,
            refreshTokenKeychainAccount(accountNo))
    }

    fun clearRefreshToken(accountNo: String) {
        keychain.delete(
            REFRESH_TOKEN_KEYCHAIN_SERVICE,
            refreshTokenKeychainAccount(accountNo))
    }

    fun mediaCache(accountNo: String): EncryptedMediaCache {
        return EncryptedMediaCache(
            accountNo = normalizeAccountNo(accountNo),
            rootDirectory = rootDirectory.resolve("media-cache"),
            keychain = keychain,
            random = random)
    }

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

    private fun refreshTokenKeychainAccount(accountNo: String): String {
        return "account:${normalizeAccountNo(accountNo)}"
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
                    access_token_expires_at, refresh_token_expires_at)
                KEY(id) VALUES (1, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
                statement.setString(1, session.accountNo)
                statement.setString(2, session.userId)
                statement.setString(3, session.deviceId)
                statement.setString(4, session.deviceClass.name)
                statement.setString(5, session.accessTokenExpiresAt)
                statement.setString(6, session.refreshTokenExpiresAt)
                statement.executeUpdate()
            }
        }
    }

    fun loadSession(): StoredSession? {
        pool.connection.use { connection ->
            connection.prepareStatement(
                "SELECT account_no, user_id, device_id, device_class, " +
                    "access_token_expires_at, refresh_token_expires_at FROM session_state WHERE id = 1")
                .use { statement ->
                    statement.executeQuery().use { result ->
                        if (!result.next()) return null
                        return StoredSession(
                            accountNo = result.getString("account_no"),
                            userId = result.getString("user_id"),
                            deviceId = result.getString("device_id"),
                            deviceClass = DeviceClass.valueOf(
                                result.getString("device_class")),
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

class EncryptedMediaCache internal constructor(
    private val accountNo: String,
    private val rootDirectory: Path,
    private val keychain: Keychain,
    private val random: SecureRandom,
) {
    companion object {
        private const val KEYCHAIN_SERVICE = "com.jitong.im.desktop.media-cache"
        private const val KEY_LENGTH_BYTES = 32
        private const val NONCE_LENGTH_BYTES = 12
    }

    private val accountDirectory: Path
        get() = rootDirectory.resolve(accountNo)

    fun put(mediaId: String, content: ByteArray): Path {
        require(mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            "mediaId contains unsupported characters"
        }
        accountDirectory.createDirectories()
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also(random::nextBytes)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                secretKey(),
                javax.crypto.spec.GCMParameterSpec(128, nonce))
        }
        val encrypted = cipher.doFinal(content)
        val path = accountDirectory.resolve("$mediaId.bin")
        Files.write(path, nonce + encrypted)
        return path
    }

    fun get(mediaId: String): ByteArray {
        require(mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            "mediaId contains unsupported characters"
        }
        val path = accountDirectory.resolve("$mediaId.bin")
        val encrypted = Files.readAllBytes(path)
        require(encrypted.size > NONCE_LENGTH_BYTES) {
            "encrypted media cache entry is truncated"
        }
        val nonce = encrypted.copyOfRange(0, NONCE_LENGTH_BYTES)
        val ciphertext = encrypted.copyOfRange(NONCE_LENGTH_BYTES, encrypted.size)
        return javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                javax.crypto.Cipher.DECRYPT_MODE,
                secretKey(),
                javax.crypto.spec.GCMParameterSpec(128, nonce))
            doFinal(ciphertext)
        }
    }

    fun clear() {
        if (accountDirectory.toFile().exists()) {
            Files.walk(accountDirectory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
            }
        }
        keychain.delete(KEYCHAIN_SERVICE, "account:$accountNo")
    }

    private fun secretKey(): javax.crypto.spec.SecretKeySpec {
        val encoded = keychain.read(KEYCHAIN_SERVICE, "account:$accountNo")
            ?: ByteArray(KEY_LENGTH_BYTES).also(random::nextBytes).let {
                Base64.getUrlEncoder().withoutPadding().encodeToString(it).also { value ->
                    keychain.write(KEYCHAIN_SERVICE, "account:$accountNo", value)
                }
            }
        return javax.crypto.spec.SecretKeySpec(
            Base64.getUrlDecoder().decode(encoded),
            "AES")
    }
}

data class StoredSession(
    val accountNo: String,
    val userId: String,
    val deviceId: String,
    val deviceClass: DeviceClass,
    val accessTokenExpiresAt: String,
    val refreshTokenExpiresAt: String,
)
