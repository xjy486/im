package com.jitong.im.android.auth

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException

internal class AuthRepository(
    private val authApi: AuthApi,
    private val authenticatedApi: AuthApi,
    private val sessionManager: SessionManager,
    private val installationIdentity: InstallationIdentity,
    private val gson: Gson = Gson(),
) {
    suspend fun login(accountNo: String, password: String) {
        val response = execute {
            authApi.login(
                LoginRequest(
                    accountNo = accountNo,
                    password = password,
                    installationId = installationIdentity.value,
                ),
            )
        }
        if (!response.isSuccessful) throw response.toAuthException()
        sessionManager.activate(response.bodyOrThrow())
    }

    suspend fun confirmReplacement(challenge: String) {
        val response = execute { authApi.confirmReplacement(ReplacementConfirmationRequest(challenge)) }
        if (!response.isSuccessful) throw response.toAuthException()
        sessionManager.activate(response.bodyOrThrow())
    }

    suspend fun restore() {
        if (sessionManager.snapshot() == null) {
            sessionManager.logout()
            return
        }
        try {
            val response = execute { authenticatedApi.validate() }
            if (response.isSuccessful) {
                // The authenticator may have rotated the session while validate() was
                // running. SessionManager reads the latest encrypted snapshot.
                sessionManager.markRestored()
            } else if (response.code() == 403
                && response.errorPayload(gson).code == "PASSWORD_CHANGE_REQUIRED"
            ) {
                sessionManager.requirePasswordChange()
            } else if (response.code() == 401 && sessionManager.shouldEraseAfterUnauthorized()) {
                sessionManager.invalidateAndErase()
            } else if (response.code() in 400..499) {
                sessionManager.invalidateAndErase()
            } else {
                // Preserve a still-present session for offline local access; a later API call
                // will run through the same bounded authenticator path.
                sessionManager.markRestored()
            }
        } catch (_: IOException) {
            sessionManager.markRestored()
        }
    }

    suspend fun logout() {
        val authorization = sessionManager.snapshot()?.accessToken?.let { "Bearer $it" }
        sessionManager.prepareForLogout()
        withContext(Dispatchers.IO) {
            if (authorization != null) {
                runCatching { authApi.logout(authorization).execute() }
            }
        }
        sessionManager.finishLogout()
    }

    suspend fun clearCurrentAccount() {
        val authorization = sessionManager.snapshot()?.accessToken?.let { "Bearer $it" }
        sessionManager.prepareForLogout()
        withContext(Dispatchers.IO) {
            if (authorization != null) {
                runCatching { authApi.logout(authorization).execute() }
            }
        }
        sessionManager.clearCurrentAccount()
    }

    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ) {
        val response = execute {
            authenticatedApi.changePassword(
                PasswordChangeRequest(
                    currentPassword = currentPassword,
                    newPassword = newPassword,
                ))
        }
        if (!response.isSuccessful) {
            throw response.toAuthException()
        }
        sessionManager.activate(response.bodyOrThrow())
    }

    fun requireReplacement(exception: DeviceReplacementRequiredException) =
        sessionManager.requireReplacement(exception)

    fun showError(message: String) = sessionManager.showError(message)

    fun showPasswordChangeError(message: String) = sessionManager.showPasswordChangeError(message)

    fun requestPasswordChange() = sessionManager.requestPasswordChange()

    private suspend fun <T> execute(call: () -> retrofit2.Call<T>): Response<T> =
        withContext(Dispatchers.IO) { call().execute() }

    private fun <T> Response<T>.bodyOrThrow(): T = body()
        ?: throw AuthenticationInvalidException(code = "EMPTY_RESPONSE", message = "Authentication response was empty")

    private fun <T> Response<T>.toAuthException(): AuthException {
        val payload = errorPayload(gson)
        if (payload.code == "DEVICE_REPLACEMENT_REQUIRED" && !payload.replacementChallenge.isNullOrBlank()) {
            return DeviceReplacementRequiredException(
                challenge = payload.replacementChallenge,
                deviceClass = payload.deviceClass ?: "MOBILE",
                message = payload.message ?: "Device replacement confirmation is required",
            )
        }
        return AuthenticationInvalidException(
            statusCode = code(),
            code = payload.code ?: "HTTP_${code()}",
            message = payload.message ?: "Authentication request failed",
        )
    }
}
