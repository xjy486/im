package com.jitong.im.desktop.local

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDatabaseManagerTest {
    @Test
    fun each_account_gets_an_independent_encrypted_database_and_keychain_entry() {
        val root = createTempDirectory("jitong-local")
        val keychain = InMemoryKeychain()
        val manager = LocalDatabaseManager(root, keychain)
        val firstSession = StoredSession(
            accountNo = "12345678903",
            userId = "user-one",
            deviceId = "device-one",
            deviceClass = com.jitong.im.desktop.auth.DeviceClass.PC,
            accessTokenExpiresAt = "2026-08-19T00:15:00Z",
            refreshTokenExpiresAt = "2026-09-18T00:00:00Z")

        manager.open(firstSession.accountNo).use { it.saveSession(firstSession) }
        manager.open("22345678902").use { }

        assertTrue(manager.databaseFile(firstSession.accountNo).toFile().exists())
        assertTrue(manager.databaseFile("22345678902").toFile().exists())
        assertEquals(listOf("12345678903", "22345678902"), manager.findAccounts())

        manager.open(firstSession.accountNo).use {
            assertEquals(firstSession, it.loadSession())
        }
        manager.open("22345678902").use {
            assertNull(it.loadSession())
        }
    }

    @Test
    fun clear_removes_database_files_and_keychain_secret() {
        val root = createTempDirectory("jitong-clear")
        val keychain = InMemoryKeychain()
        val manager = LocalDatabaseManager(root, keychain)
        manager.open("12345678903").use { }
        assertTrue(manager.databaseFile("12345678903").toFile().exists())

        manager.clear("12345678903")

        assertTrue(!manager.databaseFile("12345678903").toFile().exists())
        assertTrue(manager.findAccounts().isEmpty())
    }

    @Test
    fun refresh_tokens_are_kept_in_keychain_and_media_cache_is_encrypted() {
        val root = createTempDirectory("jitong-secrets")
        val keychain = InMemoryKeychain()
        val manager = LocalDatabaseManager(root, keychain)
        val accountNo = "12345678903"

        manager.saveRefreshToken(accountNo, "refresh-secret")
        val media = manager.mediaCache(accountNo)
        val mediaFile = media.put("avatar", "private image".toByteArray())

        assertEquals("refresh-secret", manager.loadRefreshToken(accountNo))
        assertTrue(mediaFile.toFile().readBytes().toString(Charsets.UTF_8) != "private image")
        assertEquals("private image", media.get("avatar").toString(Charsets.UTF_8))

        manager.clear(accountNo)

        assertNull(manager.loadRefreshToken(accountNo))
        assertTrue(!mediaFile.toFile().exists())
    }
}
