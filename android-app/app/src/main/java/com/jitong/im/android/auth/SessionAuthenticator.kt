package com.jitong.im.android.auth

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

internal class SessionAuthenticator(
    private val sessionManager: SessionManager,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val responseCount = responseCount(response)
        val failedAccessToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
        val currentAccessToken = sessionManager.snapshot()?.accessToken

        if (responseCount >= 2) {
            sessionManager.invalidateAndErase()
            return null
        }

        if (!failedAccessToken.isNullOrBlank()
            && !currentAccessToken.isNullOrBlank()
            && currentAccessToken != failedAccessToken
        ) {
            return response.request.newBuilder()
                .header("Authorization", "Bearer $currentAccessToken")
                .build()
        }

        if (!AuthRetryPolicy.mayRefresh(responseCount, failedAccessToken, currentAccessToken)) {
            return null
        }

        val refreshedAccessToken = sessionManager.refreshFor(failedAccessToken) ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshedAccessToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
