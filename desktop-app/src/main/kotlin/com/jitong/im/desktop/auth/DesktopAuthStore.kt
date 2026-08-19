package com.jitong.im.desktop.auth

import com.jitong.im.desktop.local.LocalDatabase
import com.jitong.im.desktop.local.LocalDatabaseManager
import com.jitong.im.desktop.local.StoredSession
import java.time.Instant

sealed interface LoginOutcome {
    data class Authenticated(val session: DesktopSession) : LoginOutcome
    data class ReplacementRequired(val challenge: String, val deviceClass: DeviceClass) : LoginOutcome
}

data class DesktopSession(
    val accountNo: String,
    val userId: String,
    val deviceId: String,
    val deviceClass: DeviceClass,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshTokenExpiresAt: Instant,
)

class DesktopAuthStore(
    private val authClient: AuthGateway,
    private val databaseManager: LocalDatabaseManager,
    private val installationId: String,
) : AutoCloseable {
    private var database: LocalDatabase? = null
    var session: DesktopSession? = null
        private set

    fun login(accountNo: String, password: String): LoginOutcome {
        return try {
            val response = authClient.login(accountNo, password, installationId)
            authenticated(response)
        } catch (exception: AuthApiException) {
            val challenge = exception.error.replacementChallenge
            if (exception.statusCode == 409 && challenge != null) {
                LoginOutcome.ReplacementRequired(
                    challenge = challenge,
                    deviceClass = exception.error.deviceClass
                        ?.let { runCatching { DeviceClass.valueOf(it) }.getOrNull() }
                        ?: DeviceClass.PC)
            } else {
                throw exception
            }
        }
    }

    fun confirmReplacement(challenge: String): DesktopSession {
        return authenticated(authClient.confirmReplacement(challenge)).session
    }

    fun restore(): DesktopSession? {
        val storedAccount = databaseManager.findAccounts().firstOrNull { account ->
            databaseManager.open(account).use { it.loadSession() != null }
        } ?: return null
        val local = databaseManager.open(storedAccount)
        database = local
        val stored = local.loadSession() ?: return null
        val refreshToken = databaseManager.loadRefreshToken(stored.accountNo)
            ?: return clearUntrustedLocalDataAndReturnNull(stored.accountNo)
        return try {
            val refreshed = authClient.refresh(refreshToken)
            authenticated(refreshed).session
        } catch (exception: AuthApiException) {
            if (exception.statusCode == 401) {
                clearUntrustedLocalDataAndReturnNull(stored.accountNo)
            } else {
                throw exception
            }
        }
    }

    fun validateAccess() {
        val current = session ?: return
        try {
            authClient.validate(current.accessToken)
        } catch (exception: AuthApiException) {
            if (exception.statusCode != 401) throw exception
            try {
                val refreshed = authClient.refresh(current.refreshToken)
                authenticated(refreshed)
            } catch (refreshException: AuthApiException) {
                if (refreshException.statusCode == 401) {
                    clearUntrustedLocalData(current.accountNo)
                    session = null
                }
                throw refreshException
            }
        }
    }

    fun logout() {
        val current = session
        if (current != null) {
            runCatching { authClient.logout(current.accessToken) }
        }
        database?.clearSession()
        current?.let { databaseManager.clearRefreshToken(it.accountNo) }
        session = null
        database?.close()
        database = null
    }

    fun clearUntrustedLocalData() {
        val accountNo = session?.accountNo ?: return
        clearUntrustedLocalData(accountNo)
        session = null
        database = null
    }

    private fun clearUntrustedLocalData(accountNo: String) {
        database?.close()
        database = null
        databaseManager.clear(accountNo)
    }

    private fun clearUntrustedLocalDataAndReturnNull(accountNo: String): Nothing? {
        clearUntrustedLocalData(accountNo)
        session = null
        return null
    }

    private fun authenticated(response: LoginResponse): LoginOutcome.Authenticated {
        val next = DesktopSession(
            accountNo = response.accountNo,
            userId = response.userId,
            deviceId = response.deviceId,
            deviceClass = response.deviceClass,
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            accessTokenExpiresAt = Instant.parse(response.accessTokenExpiresAt),
            refreshTokenExpiresAt = Instant.parse(response.refreshTokenExpiresAt))
        database?.close()
        database = databaseManager.open(next.accountNo)
        database!!.saveSession(next.toStored())
        databaseManager.saveRefreshToken(next.accountNo, next.refreshToken)
        session = next
        return LoginOutcome.Authenticated(next)
    }

    override fun close() {
        database?.close()
    }
}

private fun DesktopSession.toStored() = StoredSession(
    accountNo = accountNo,
    userId = userId,
    deviceId = deviceId,
    deviceClass = deviceClass,
    accessTokenExpiresAt = accessTokenExpiresAt.toString(),
    refreshTokenExpiresAt = refreshTokenExpiresAt.toString())
