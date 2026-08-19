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
            deviceClass = "PC",
            accessToken = "access-one",
            refreshToken = "refresh-one",
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
}
