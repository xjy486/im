package com.jitong.im.desktop.local

import com.jitong.im.desktop.auth.DeviceClass
import org.h2.jdbcx.JdbcConnectionPool
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.sql.ResultSet
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
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS desktop_sync_state (
                            id INT PRIMARY KEY,
                            last_sync_seq BIGINT NOT NULL
                        )
                        """.trimIndent())
                    statement.executeUpdate(
                        """
                        INSERT INTO desktop_sync_state (id, last_sync_seq)
                        SELECT 1, 0
                        WHERE NOT EXISTS (
                            SELECT 1 FROM desktop_sync_state WHERE id = 1
                        )
                        """.trimIndent())
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS local_conversations (
                            conversation_id VARCHAR(36) PRIMARY KEY,
                            peer_user_id VARCHAR(36) NOT NULL,
                            peer_account_no VARCHAR(11) NOT NULL,
                            peer_display_name VARCHAR(255) NOT NULL,
                            peer_avatar_url VARCHAR(1000),
                            peer_avatar_version BIGINT NOT NULL DEFAULT 0,
                            peer_avatar_fallback VARCHAR(8) NOT NULL DEFAULT '?',
                            status VARCHAR(32) NOT NULL,
                            relationship VARCHAR(32) NOT NULL,
                            blocked_by_me BOOLEAN NOT NULL,
                            read_seq BIGINT NOT NULL,
                            peer_read_seq BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL
                        )
                        """.trimIndent())
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS local_messages (
                            message_id VARCHAR(64) PRIMARY KEY,
                            conversation_id VARCHAR(36) NOT NULL,
                            sender_id VARCHAR(36) NOT NULL,
                            client_msg_id VARCHAR(36) NOT NULL,
                            conversation_seq BIGINT,
                            type VARCHAR(32) NOT NULL,
                            state VARCHAR(32) NOT NULL,
                            local_state VARCHAR(32) NOT NULL,
                            text_content CLOB NOT NULL,
                            server_accepted_at VARCHAR(64),
                            created_at BIGINT NOT NULL,
                            UNIQUE (conversation_id, client_msg_id)
                        )
                        """.trimIndent())
                    statement.executeUpdate(
                        """
                        CREATE INDEX IF NOT EXISTS local_messages_conversation_idx
                        ON local_messages (conversation_id, conversation_seq, created_at)
                        """.trimIndent())
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS local_read_states (
                            conversation_id VARCHAR(36) NOT NULL,
                            user_id VARCHAR(36) NOT NULL,
                            read_seq BIGINT NOT NULL,
                            PRIMARY KEY (conversation_id, user_id)
                        )
                        """.trimIndent())
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS local_group_profiles (
                            conversation_id VARCHAR(36) PRIMARY KEY,
                            avatar_url VARCHAR(1000),
                            avatar_version BIGINT NOT NULL DEFAULT 0
                        )
                        """.trimIndent())
                    statement.executeUpdate(
                        "ALTER TABLE local_conversations ADD COLUMN IF NOT EXISTS peer_avatar_url VARCHAR(1000)")
                    statement.executeUpdate(
                        "ALTER TABLE local_conversations ADD COLUMN IF NOT EXISTS peer_avatar_version BIGINT NOT NULL DEFAULT 0")
                    statement.executeUpdate(
                        "ALTER TABLE local_conversations ADD COLUMN IF NOT EXISTS peer_avatar_fallback VARCHAR(8) NOT NULL DEFAULT '?'")
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
            pool = pool,
            mediaCache = mediaCache(normalizedAccount))
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
    private val mediaCache: EncryptedMediaCache,
) : AutoCloseable {
    fun mediaCache(): EncryptedMediaCache = mediaCache
    fun lastSyncSeq(): Long {
        pool.connection.use { connection ->
            connection.prepareStatement(
                "SELECT last_sync_seq FROM desktop_sync_state WHERE id = 1")
                .use { statement ->
                    statement.executeQuery().use { result ->
                        if (!result.next()) return 0
                        return result.getLong("last_sync_seq")
                    }
                }
        }
    }

    fun saveLastSyncSeq(syncSeq: Long) {
        require(syncSeq >= 0) { "syncSeq must not be negative" }
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO desktop_sync_state (id, last_sync_seq)
                KEY(id) VALUES (1, ?)
                """.trimIndent())
                .use { statement ->
                    statement.setLong(1, syncSeq)
                    statement.executeUpdate()
                }
        }
    }

    fun upsertConversation(conversation: LocalConversation) {
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO local_conversations (
                    conversation_id, peer_user_id, peer_account_no, peer_display_name,
                    peer_avatar_url, peer_avatar_version, peer_avatar_fallback,
                    status, relationship, blocked_by_me, read_seq, peer_read_seq, updated_at
                ) KEY(conversation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
                statement.setString(1, conversation.conversationId)
                statement.setString(2, conversation.peerUserId)
                statement.setString(3, conversation.peerAccountNo)
                statement.setString(4, conversation.peerDisplayName)
                statement.setString(5, conversation.peerAvatarUrl)
                statement.setLong(6, conversation.peerAvatarVersion)
                statement.setString(7, conversation.peerAvatarFallback)
                statement.setString(8, conversation.status)
                statement.setString(9, conversation.relationship)
                statement.setBoolean(10, conversation.blockedByMe)
                statement.setLong(11, conversation.readSeq)
                statement.setLong(12, conversation.peerReadSeq)
                statement.setLong(13, conversation.updatedAt)
                statement.executeUpdate()
            }
        }
    }

    fun replaceConversations(conversations: List<LocalConversation>) {
        pool.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM local_conversations")
                }
                connection.prepareStatement(
                    """
                    INSERT INTO local_conversations (
                        conversation_id, peer_user_id, peer_account_no, peer_display_name,
                        peer_avatar_url, peer_avatar_version, peer_avatar_fallback,
                        status, relationship, blocked_by_me, read_seq, peer_read_seq, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()).use { statement ->
                    conversations.forEach { conversation ->
                        statement.setString(1, conversation.conversationId)
                        statement.setString(2, conversation.peerUserId)
                        statement.setString(3, conversation.peerAccountNo)
                        statement.setString(4, conversation.peerDisplayName)
                        statement.setString(5, conversation.peerAvatarUrl)
                        statement.setLong(6, conversation.peerAvatarVersion)
                        statement.setString(7, conversation.peerAvatarFallback)
                        statement.setString(8, conversation.status)
                        statement.setString(9, conversation.relationship)
                        statement.setBoolean(10, conversation.blockedByMe)
                        statement.setLong(11, conversation.readSeq)
                        statement.setLong(12, conversation.peerReadSeq)
                        statement.setLong(13, conversation.updatedAt)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (exception: RuntimeException) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun updateConversationReadProgress(
        conversationId: String,
        readSeq: Long,
        peerReadSeq: Long,
    ) {
        require(readSeq >= 0) { "readSeq must not be negative" }
        require(peerReadSeq >= 0) { "peerReadSeq must not be negative" }
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE local_conversations
                SET read_seq = GREATEST(read_seq, ?),
                    peer_read_seq = GREATEST(peer_read_seq, ?),
                    updated_at = ?
                WHERE conversation_id = ?
                """.trimIndent()).use { statement ->
                statement.setLong(1, readSeq)
                statement.setLong(2, peerReadSeq)
                statement.setLong(3, System.currentTimeMillis())
                statement.setString(4, conversationId)
                statement.executeUpdate()
            }
        }
    }

    fun updatePeerProfile(
        userId: String,
        displayName: String,
        avatarUrl: String?,
        avatarVersion: Long,
        avatarFallback: String,
    ) {
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE local_conversations
                SET peer_display_name = ?,
                    peer_avatar_url = ?,
                    peer_avatar_version = ?,
                    peer_avatar_fallback = ?,
                    updated_at = ?
                WHERE peer_user_id = ?
                """.trimIndent()).use { statement ->
                statement.setString(1, displayName)
                statement.setString(2, avatarUrl)
                statement.setLong(3, avatarVersion)
                statement.setString(4, avatarFallback)
                statement.setLong(5, System.currentTimeMillis())
                statement.setString(6, userId)
                statement.executeUpdate()
            }
        }
    }

    fun upsertGroupProfile(profile: LocalGroupProfile) {
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO local_group_profiles (
                    conversation_id, avatar_url, avatar_version
                ) KEY(conversation_id) VALUES (?, ?, ?)
                """.trimIndent()).use { statement ->
                statement.setString(1, profile.conversationId)
                statement.setString(2, profile.avatarUrl)
                statement.setLong(3, profile.avatarVersion)
                statement.executeUpdate()
            }
        }
    }

    fun groupProfile(conversationId: String): LocalGroupProfile? {
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT conversation_id, avatar_url, avatar_version
                FROM local_group_profiles
                WHERE conversation_id = ?
                """.trimIndent()).use { statement ->
                statement.setString(1, conversationId)
                statement.executeQuery().use { result ->
                    if (!result.next()) return null
                    return LocalGroupProfile(
                        conversationId = result.getString("conversation_id"),
                        avatarUrl = result.getString("avatar_url"),
                        avatarVersion = result.getLong("avatar_version"))
                }
            }
        }
    }

    fun listConversations(): List<LocalConversation> {
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT conversation_id, peer_user_id, peer_account_no, peer_display_name,
                       peer_avatar_url, peer_avatar_version, peer_avatar_fallback,
                       status, relationship, blocked_by_me, read_seq, peer_read_seq, updated_at
                FROM local_conversations
                ORDER BY updated_at DESC, peer_display_name, peer_account_no
                """.trimIndent()).use { statement ->
                statement.executeQuery().use { result ->
                    return buildList {
                        while (result.next()) {
                            add(result.toLocalConversation())
                        }
                    }
                }
            }
        }
    }

    fun upsertMessage(message: LocalMessage) {
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO local_messages (
                    message_id, conversation_id, sender_id, client_msg_id, conversation_seq,
                    type, state, local_state, text_content, server_accepted_at, created_at
                ) KEY(message_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
                statement.setString(1, message.messageId)
                statement.setString(2, message.conversationId)
                statement.setString(3, message.senderId)
                statement.setString(4, message.clientMsgId)
                if (message.conversationSeq == null) {
                    statement.setObject(5, null)
                } else {
                    statement.setLong(5, message.conversationSeq)
                }
                statement.setString(6, message.type)
                statement.setString(7, message.state)
                statement.setString(8, message.localState)
                statement.setString(9, message.text)
                statement.setString(10, message.serverAcceptedAt)
                statement.setLong(11, message.createdAt)
                statement.executeUpdate()
            }
        }
    }

    fun replaceMessageByClientId(message: LocalMessage) {
        pool.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "DELETE FROM local_messages WHERE conversation_id = ? AND client_msg_id = ?")
                    .use { statement ->
                        statement.setString(1, message.conversationId)
                        statement.setString(2, message.clientMsgId)
                        statement.executeUpdate()
                    }
                connection.prepareStatement(
                    """
                    MERGE INTO local_messages (
                        message_id, conversation_id, sender_id, client_msg_id, conversation_seq,
                        type, state, local_state, text_content, server_accepted_at, created_at
                    ) KEY(message_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()).use { statement ->
                    statement.setString(1, message.messageId)
                    statement.setString(2, message.conversationId)
                    statement.setString(3, message.senderId)
                    statement.setString(4, message.clientMsgId)
                    if (message.conversationSeq == null) {
                        statement.setObject(5, null)
                    } else {
                        statement.setLong(5, message.conversationSeq)
                    }
                    statement.setString(6, message.type)
                    statement.setString(7, message.state)
                    statement.setString(8, message.localState)
                    statement.setString(9, message.text)
                    statement.setString(10, message.serverAcceptedAt)
                    statement.setLong(11, message.createdAt)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (exception: RuntimeException) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun markMessageFailed(conversationId: String, clientMsgId: String) {
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE local_messages
                SET local_state = 'FAILED'
                WHERE conversation_id = ? AND client_msg_id = ?
                """.trimIndent()).use { statement ->
                statement.setString(1, conversationId)
                statement.setString(2, clientMsgId)
                statement.executeUpdate()
            }
        }
    }

    fun listMessages(conversationId: String): List<LocalMessage> {
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT message_id, conversation_id, sender_id, client_msg_id, conversation_seq,
                       type, state, local_state, text_content, server_accepted_at, created_at
                FROM local_messages
                WHERE conversation_id = ?
                ORDER BY CASE WHEN conversation_seq IS NULL THEN 1 ELSE 0 END,
                         conversation_seq, created_at, message_id
                """.trimIndent()).use { statement ->
                statement.setString(1, conversationId)
                statement.executeQuery().use { result ->
                    return buildList {
                        while (result.next()) {
                            add(result.toLocalMessage())
                        }
                    }
                }
            }
        }
    }

    fun lastConversationSeq(conversationId: String): Long {
        pool.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COALESCE(MAX(conversation_seq), 0) FROM local_messages WHERE conversation_id = ?")
                .use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { result ->
                        result.next()
                        return result.getLong(1)
                    }
                }
        }
    }

    fun upsertReadState(state: LocalReadState) {
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO local_read_states (conversation_id, user_id, read_seq)
                KEY(conversation_id, user_id)
                VALUES (?, ?, ?)
                """.trimIndent()).use { statement ->
                statement.setString(1, state.conversationId)
                statement.setString(2, state.userId)
                statement.setLong(3, state.readSeq)
                statement.executeUpdate()
            }
        }
    }

    fun readState(conversationId: String, userId: String): Long {
        pool.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT read_seq FROM local_read_states
                WHERE conversation_id = ? AND user_id = ?
                """.trimIndent()).use { statement ->
                statement.setString(1, conversationId)
                statement.setString(2, userId)
                statement.executeQuery().use { result ->
                    return if (result.next()) result.getLong("read_seq") else 0
                }
            }
        }
    }

    fun clearMessageData() {
        pool.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM local_messages")
                statement.executeUpdate("DELETE FROM local_conversations")
                statement.executeUpdate("DELETE FROM local_read_states")
            }
        }
    }

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

    private fun ResultSet.toLocalConversation() = LocalConversation(
        conversationId = getString("conversation_id"),
        peerUserId = getString("peer_user_id"),
        peerAccountNo = getString("peer_account_no"),
        peerDisplayName = getString("peer_display_name"),
        peerAvatarUrl = getString("peer_avatar_url"),
        peerAvatarVersion = getLong("peer_avatar_version"),
        peerAvatarFallback = getString("peer_avatar_fallback"),
        status = getString("status"),
        relationship = getString("relationship"),
        blockedByMe = getBoolean("blocked_by_me"),
        readSeq = getLong("read_seq"),
        peerReadSeq = getLong("peer_read_seq"),
        updatedAt = getLong("updated_at"))

    private fun ResultSet.toLocalMessage() = LocalMessage(
        messageId = getString("message_id"),
        conversationId = getString("conversation_id"),
        senderId = getString("sender_id"),
        clientMsgId = getString("client_msg_id"),
        conversationSeq = getLong("conversation_seq").let {
            if (wasNull()) null else it
        },
        type = getString("type"),
        state = getString("state"),
        localState = getString("local_state"),
        text = getString("text_content"),
        serverAcceptedAt = getString("server_accepted_at"),
        createdAt = getLong("created_at"))
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

    fun getOrNull(mediaId: String): ByteArray? =
        runCatching { get(mediaId) }.getOrNull()

    fun deleteMatching(prefix: String, keepMediaId: String? = null) {
        if (!accountDirectory.toFile().isDirectory) return
        Files.list(accountDirectory).use { paths ->
            paths.filter { path ->
                path.fileName.toString().startsWith(prefix)
                    && (keepMediaId == null || path.fileName.toString() != "$keepMediaId.bin")
            }.forEach { it.deleteIfExists() }
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

data class LocalConversation(
    val conversationId: String,
    val peerUserId: String,
    val peerAccountNo: String,
    val peerDisplayName: String,
    val peerAvatarUrl: String? = null,
    val peerAvatarVersion: Long = 0,
    val peerAvatarFallback: String = "?",
    val status: String,
    val relationship: String,
    val blockedByMe: Boolean,
    val readSeq: Long,
    val peerReadSeq: Long,
    val updatedAt: Long,
)

data class LocalGroupProfile(
    val conversationId: String,
    val avatarUrl: String?,
    val avatarVersion: Long,
)

data class LocalMessage(
    val messageId: String,
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

data class LocalReadState(
    val conversationId: String,
    val userId: String,
    val readSeq: Long,
)
