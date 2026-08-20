package com.jitong.im.android.push

import java.io.IOException

internal class PushTokenRepository(
    private val api: PushTokenApi,
) {
    suspend fun register(token: String, tokenVersion: Long) {
        if (token.isBlank()) return
        val response = api.update(PushTokenRequest(token, tokenVersion))
        if (!response.isSuccessful) {
            throw IOException("Push token registration failed with HTTP ${response.code()}")
        }
    }
}
