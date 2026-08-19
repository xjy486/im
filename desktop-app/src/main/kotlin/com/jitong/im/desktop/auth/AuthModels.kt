package com.jitong.im.desktop.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DeviceClass {
    MOBILE,
    PC,
}

@Serializable
data class LoginRequest(
    val accountNo: String,
    val password: String,
    val deviceClass: DeviceClass = DeviceClass.PC,
    val installationId: String,
)

@Serializable
data class LoginResponse(
    val version: Int,
    val userId: String,
    val accountNo: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: String,
    val refreshTokenExpiresAt: String,
    val deviceId: String,
    val deviceClass: DeviceClass,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class ReplacementConfirmationRequest(val replacementChallenge: String)

@Serializable
data class ApiErrorResponse(
    val version: Int = 1,
    val code: String,
    val message: String,
    val requestId: String? = null,
    val timestamp: String? = null,
    val replacementChallenge: String? = null,
    val deviceClass: String? = null,
)

class AuthApiException(
    val statusCode: Int,
    val error: ApiErrorResponse,
) : IllegalStateException("${error.code}: ${error.message}")
