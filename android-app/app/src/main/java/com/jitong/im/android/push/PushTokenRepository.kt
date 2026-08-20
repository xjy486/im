package com.jitong.im.android.push

import java.io.IOException

internal class PushTokenRepository(
    private val api: PushTokenApi,
) {
    suspend fun register(token: String) {
        if (token.isBlank()) return
        val response = api.update(PushTokenRequest(token))
        if (!response.isSuccessful) {
            throw IOException("Push token registration failed with HTTP ${response.code()}")
        }
    }
}
