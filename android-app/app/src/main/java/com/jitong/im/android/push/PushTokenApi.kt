package com.jitong.im.android.push

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

internal interface PushTokenApi {
    @POST("api/v1/devices/push-token")
    suspend fun update(@Body request: PushTokenRequest): Response<Unit>
}

internal data class PushTokenRequest(
    val token: String,
    val tokenVersion: Long,
)
