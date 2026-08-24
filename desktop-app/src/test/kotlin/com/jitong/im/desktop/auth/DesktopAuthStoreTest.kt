package com.jitong.im.desktop.auth

import com.jitong.im.desktop.local.InMemoryKeychain
import com.jitong.im.desktop.local.LocalDatabaseManager
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAuthStoreTest {
    @Test
    fun login_persists_session_and_restore_rotates_refresh_token_after_restart() {
        val root = createTempDirectory("jitong-auth")
        val gateway = FakeAuthGateway()
        val manager = LocalDatabaseManager(root, InMemoryKeychain())
        val first = DesktopAuthStore(manager = manager, gateway = gateway)

        val login = assertIs<LoginOutcome.Authenticated>(first.login("12345678903", "password"))
        assertEquals(DeviceClass.PC, login.session.deviceClass)
        assertEquals("refresh", manager.loadRefreshToken("12345678903"))
        first.close()

        val restarted = DesktopAuthStore(manager = manager, gateway = gateway)
        val restored = restarted.restore()

        assertEquals(login.session.accountNo, restored?.accountNo)
        assertEquals(1, gateway.refreshCalls)
        assertEquals("rotated-refresh", restored?.refreshToken)
        assertEquals("rotated-refresh", manager.loadRefreshToken("12345678903"))
        restarted.close()
    }

    @Test
    fun normal_logout_clears_session_but_keeps_the_encrypted_database() {
        val root = createTempDirectory("jitong-logout")
        val manager = LocalDatabaseManager(root, InMemoryKeychain())
        val store = DesktopAuthStore(manager = manager, gateway = FakeAuthGateway())

        store.login("12345678903", "password")
        store.logout()

        assertNull(store.session)
        assertNull(manager.loadRefreshToken("12345678903"))
        assertTrue(manager.databaseFile("12345678903").toFile().exists())
    }

    @Test
    fun account_deletion_clears_the_local_database_and_refresh_credential() {
        val root = createTempDirectory("jitong-account-deletion")
        val manager = LocalDatabaseManager(root, InMemoryKeychain())
        val gateway = FakeAuthGateway()
        val store = DesktopAuthStore(manager = manager, gateway = gateway)

        store.login("12345678903", "password")
        store.deleteAccount("password")

        assertNull(store.session)
        assertNull(manager.loadRefreshToken("12345678903"))
        assertTrue(!manager.databaseFile("12345678903").toFile().exists())
        assertEquals(1, gateway.deleteAccountCalls)
    }

    @Test
    fun invalid_refresh_clears_untrusted_account_data() {
        val root = createTempDirectory("jitong-untrusted")
        val manager = LocalDatabaseManager(root, InMemoryKeychain())
        val store = DesktopAuthStore(manager = manager, gateway = FakeAuthGateway(refreshFails = true))

        store.login("12345678903", "password")
        val restored = DesktopAuthStore(manager = manager, gateway = FakeAuthGateway(refreshFails = true))
            .restore()

        assertNull(restored)
        assertTrue(!manager.databaseFile("12345678903").toFile().exists())
    }

    @Test
    fun rejected_access_and_refresh_clear_untrusted_local_state() {
        val root = createTempDirectory("jitong-validate")
        val manager = LocalDatabaseManager(root, InMemoryKeychain())
        val store = DesktopAuthStore(
            manager = manager,
            gateway = FakeAuthGateway(refreshFails = true, validateFails = true))

        store.login("12345678903", "password")
        assertFailsWith<AuthApiException> { store.validateAccess() }

        assertNull(store.session)
        assertNull(manager.loadRefreshToken("12345678903"))
        assertTrue(!manager.databaseFile("12345678903").toFile().exists())
    }

    @Test
    fun replacement_challenge_is_exposed_without_touching_local_state() {
        val gateway = FakeAuthGateway(replacementRequired = true)
        val store = DesktopAuthStore(
            manager = LocalDatabaseManager(createTempDirectory("jitong-replace"), InMemoryKeychain()),
            gateway = gateway)

        val result = assertIs<LoginOutcome.ReplacementRequired>(store.login("12345678903", "password"))

        assertEquals("challenge", result.challenge)
        assertEquals(DeviceClass.PC, result.deviceClass)
        assertNull(store.session)
    }

    private class FakeAuthGateway(
        private val replacementRequired: Boolean = false,
        private val refreshFails: Boolean = false,
        private val validateFails: Boolean = false,
    ) : AuthGateway {
        var refreshCalls = 0
        var deleteAccountCalls = 0

        override fun login(accountNo: String, password: String, installationId: String): LoginResponse {
            if (replacementRequired) {
                throw AuthApiException(
                    409,
                    ApiErrorResponse(
                        code = "DEVICE_REPLACEMENT_REQUIRED",
                        message = "replacement",
                        replacementChallenge = "challenge",
                        deviceClass = "PC"))
            }
            return response("access", "refresh")
        }

        override fun refresh(refreshToken: String): LoginResponse {
            refreshCalls++
            if (refreshFails) {
                throw AuthApiException(401, ApiErrorResponse(code = "AUTH_INVALID", message = "invalid"))
            }
            return response("rotated-access", "rotated-refresh")
        }

        override fun validate(accessToken: String) {
            if (validateFails) {
                throw AuthApiException(401, ApiErrorResponse(code = "TOKEN_EXPIRED", message = "expired"))
            }
        }

        override fun confirmReplacement(challenge: String): LoginResponse = response("replacement-access", "replacement-refresh")

        override fun logout(accessToken: String) = Unit

        override fun deleteAccount(accessToken: String, currentPassword: String) {
            deleteAccountCalls++
        }

        override fun changePassword(
            accessToken: String,
            currentPassword: String,
            newPassword: String,
        ): LoginResponse = response("changed-access", "changed-refresh")

        private fun response(access: String, refresh: String) = LoginResponse(
            version = 1,
            userId = "user-id",
            accountNo = "12345678903",
            accessToken = access,
            refreshToken = refresh,
            accessTokenExpiresAt = "2026-08-19T00:15:00Z",
            refreshTokenExpiresAt = "2026-09-18T00:00:00Z",
            deviceId = "device-id",
            deviceClass = DeviceClass.PC)
    }

    private fun DesktopAuthStore(
        manager: LocalDatabaseManager,
        gateway: AuthGateway,
    ) = DesktopAuthStore(
        authClient = gateway,
        databaseManager = manager,
        installationId = "installation-id")
}
