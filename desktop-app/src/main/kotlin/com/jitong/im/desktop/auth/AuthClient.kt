package com.jitong.im.desktop.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AuthClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AuthGateway {
    override fun login(accountNo: String, password: String, installationId: String): LoginResponse =
        post(
            path = "/api/v1/auth/login",
            body = LoginRequest(
                accountNo = accountNo,
                password = password,
                deviceClass = "PC",
                installationId = installationId))

    override fun refresh(refreshToken: String): LoginResponse =
        post("/api/v1/auth/refresh", RefreshRequest(refreshToken))

    override fun validate(accessToken: String) {
        val request = Request.Builder()
            .url(url("/api/v1/auth/validate"))
            .header("Authorization", "Bearer $accessToken")
            .post("".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request)
    }

    override fun confirmReplacement(challenge: String): LoginResponse =
        post(
            "/api/v1/auth/device-replacement/confirm",
            ReplacementConfirmationRequest(challenge))

    private inline fun <reified T> post(path: String, body: T): LoginResponse {
        val request = Request.Builder()
            .url(url(path))
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeFromString(execute(request))
    }

    private fun execute(request: Request): String {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) return body
            val error = runCatching { json.decodeFromString<ApiErrorResponse>(body) }
                .getOrElse {
                    ApiErrorResponse(
                        code = "HTTP_${response.code}",
                        message = "The server returned HTTP ${response.code}")
                }
            throw AuthApiException(response.code, error)
        }
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
