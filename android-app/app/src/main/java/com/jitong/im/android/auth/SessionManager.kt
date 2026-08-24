package com.jitong.im.android.auth

import com.jitong.im.android.local.AccountLocalStore
import com.jitong.im.android.security.SecureSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface SessionState {
    data object SignedOut : SessionState
    data object Restoring : SessionState
    data class SignedIn(val session: SessionSnapshot) : SessionState
    data class PasswordChangeRequired(
        val session: SessionSnapshot,
        val temporaryPasswordRequired: Boolean,
        val error: String? = null,
    ) : SessionState
    data class ReplacementRequired(
        val challenge: String,
        val deviceClass: String,
    ) : SessionState
    data class Error(val message: String) : SessionState
}

internal class SessionManager(
    private val authApi: AuthApi,
    private val sessionStore: SecureSessionStore,
    private val localStore: AccountLocalStore,
) {
    private enum class RefreshOutcome {
        NOT_ATTEMPTED,
        SUCCEEDED,
        INVALID,
        TRANSIENT,
    }

    private val _state = MutableStateFlow<SessionState>(
        if (sessionStore.read() == null) SessionState.SignedOut else SessionState.Restoring,
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private var lastRefreshOutcome = RefreshOutcome.NOT_ATTEMPTED
    private var beforeLogout: suspend () -> Unit = {}

    fun snapshot(): SessionSnapshot? = sessionStore.read()

    fun setBeforeLogout(callback: suspend () -> Unit) {
        beforeLogout = callback
    }

    suspend fun activate(response: LoginResponse) {
        val snapshot = response.toSessionSnapshot()
        sessionStore.write(snapshot)
        withContext(Dispatchers.IO) {
            localStore.ensureAccount(snapshot)
        }
        _state.value = if (snapshot.passwordMustChange) {
            SessionState.PasswordChangeRequired(snapshot, temporaryPasswordRequired = true)
        } else {
            SessionState.SignedIn(snapshot)
        }
    }

    suspend fun markRestored() {
        val current = sessionStore.read() ?: run {
            _state.value = SessionState.SignedOut
            return
        }
        withContext(Dispatchers.IO) {
            localStore.ensureAccount(current)
        }
        _state.value = if (_state.value is SessionState.PasswordChangeRequired) {
            (_state.value as SessionState.PasswordChangeRequired).copy(
                session = current,
                temporaryPasswordRequired = current.passwordMustChange,
            )
        } else if (current.passwordMustChange) {
            SessionState.PasswordChangeRequired(current, temporaryPasswordRequired = true)
        } else {
            SessionState.SignedIn(current)
        }
    }

    @Synchronized
    fun requireReplacement(exception: DeviceReplacementRequiredException) {
        _state.value = SessionState.ReplacementRequired(exception.challenge, exception.deviceClass)
    }

    @Synchronized
    fun requirePasswordChange() {
        val current = sessionStore.read() ?: return
        _state.value = SessionState.PasswordChangeRequired(
            session = current,
            temporaryPasswordRequired = current.passwordMustChange,
        )
    }

    @Synchronized
    fun showError(message: String) {
        _state.value = SessionState.Error(message)
    }

    @Synchronized
    fun requestPasswordChange() {
        val current = sessionStore.read() ?: return
        _state.value = SessionState.PasswordChangeRequired(
            current,
            temporaryPasswordRequired = false,
        )
    }

    @Synchronized
    fun showPasswordChangeError(message: String) {
        val current = sessionStore.read() ?: return
        _state.value = SessionState.PasswordChangeRequired(
            current,
            temporaryPasswordRequired = current.passwordMustChange,
            error = message,
        )
    }

    /** Normal logout: credentials disappear, but the account database and media cache stay. */
    suspend fun prepareForLogout() {
        beforeLogout()
    }

    suspend fun finishLogout() {
        withContext(Dispatchers.IO) {
            localStore.closeActive()
        }
        sessionStore.clear()
        _state.value = SessionState.SignedOut
    }

    suspend fun logout() {
        prepareForLogout()
        finishLogout()
    }

    suspend fun clearCurrentAccount() {
        val snapshot = sessionStore.read()
        if (snapshot != null) {
            withContext(Dispatchers.IO) {
                localStore.forgetAccount(snapshot.accountNo)
            }
        }
        sessionStore.clear()
        _state.value = SessionState.SignedOut
    }

    @Synchronized
    fun invalidateAndErase() {
        val snapshot = sessionStore.read()
        if (snapshot != null) localStore.forgetAccount(snapshot.accountNo)
        sessionStore.clear()
        _state.value = SessionState.SignedOut
    }

    @Synchronized
    fun shouldEraseAfterUnauthorized(): Boolean =
        lastRefreshOutcome != RefreshOutcome.TRANSIENT

    /** Called by OkHttp's authenticator on a network thread. It performs at most one refresh. */
    @Synchronized
    fun refreshFor(failedAccessToken: String?): String? {
        val current = sessionStore.read() ?: return null
        if (!failedAccessToken.isNullOrBlank() && failedAccessToken != current.accessToken) {
            lastRefreshOutcome = RefreshOutcome.SUCCEEDED
            return current.accessToken
        }

        val response = try {
            authApi.refresh(RefreshRequest(current.refreshToken)).execute()
        } catch (_: IOException) {
            // A transport failure is not evidence that the device is untrusted.
            // Keep the encrypted local account available for offline use.
            lastRefreshOutcome = RefreshOutcome.TRANSIENT
            return null
        }
        if (response.code() == 401) {
            lastRefreshOutcome = RefreshOutcome.INVALID
            invalidateAndErase()
            return null
        }
        if (!response.isSuccessful || response.body() == null) {
            lastRefreshOutcome = RefreshOutcome.TRANSIENT
            return null
        }

        val refreshed = response.body()!!
        val snapshot = refreshed.toSessionSnapshot()
        sessionStore.write(snapshot)
        _state.value = if (_state.value is SessionState.PasswordChangeRequired) {
            (_state.value as SessionState.PasswordChangeRequired).copy(
                session = snapshot,
                temporaryPasswordRequired = snapshot.passwordMustChange,
            )
        } else if (snapshot.passwordMustChange) {
            SessionState.PasswordChangeRequired(snapshot, temporaryPasswordRequired = true)
        } else {
            SessionState.SignedIn(snapshot)
        }
        lastRefreshOutcome = RefreshOutcome.SUCCEEDED
        return snapshot.accessToken
    }

}
