package com.jitong.im.android.auth

import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

internal interface AuthApi {
    @POST("api/v1/auth/login")
    fun login(@Body request: LoginRequest): Call<LoginResponse>

    @POST("api/v1/auth/device-replacement/confirm")
    fun confirmReplacement(@Body request: ReplacementConfirmationRequest): Call<LoginResponse>

    @POST("api/v1/auth/refresh")
    fun refresh(@Body request: RefreshRequest): Call<LoginResponse>

    @POST("api/v1/auth/validate")
    fun validate(): Call<Void>
}

internal fun <T> Response<T>.errorPayload(gson: com.google.gson.Gson): ApiErrorPayload {
    val body = errorBody()?.string().orEmpty()
    return runCatching { gson.fromJson(body, ApiErrorPayload::class.java) }
        .getOrNull()
        ?: ApiErrorPayload(code = "HTTP_${code()}", message = "Authentication request failed")
}
