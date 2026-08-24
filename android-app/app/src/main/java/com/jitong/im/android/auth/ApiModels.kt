package com.jitong.im.android.auth

import com.google.gson.annotations.SerializedName

internal data class LoginRequest(
    val accountNo: String,
    val password: String,
    val deviceClass: String = "MOBILE",
    val installationId: String,
)

internal data class ReplacementConfirmationRequest(
    val replacementChallenge: String,
)

internal data class RefreshRequest(
    val refreshToken: String,
)

internal data class PasswordChangeRequest(
    val currentPassword: String,
    val newPassword: String,
)

data class LoginResponse(
    val version: Int,
    val userId: String,
    val accountNo: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: String,
    val refreshTokenExpiresAt: String,
    val deviceId: String,
    val deviceClass: String,
    val passwordMustChange: Boolean = false,
)

internal data class ApiErrorPayload(
    val version: Int? = null,
    val code: String? = null,
    val message: String? = null,
    val requestId: String? = null,
    val timestamp: String? = null,
    @SerializedName("replacementChallenge")
    val replacementChallenge: String? = null,
    val deviceClass: String? = null,
)

data class SessionSnapshot(
    val userId: String,
    val accountNo: String,
    val deviceId: String,
    val deviceClass: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: String,
    val refreshTokenExpiresAt: String,
    val passwordMustChange: Boolean = false,
)

internal fun LoginResponse.toSessionSnapshot(): SessionSnapshot = SessionSnapshot(
    userId = userId,
    accountNo = accountNo,
    deviceId = deviceId,
    deviceClass = deviceClass,
    accessToken = accessToken,
    refreshToken = refreshToken,
    accessTokenExpiresAt = accessTokenExpiresAt,
    refreshTokenExpiresAt = refreshTokenExpiresAt,
    passwordMustChange = passwordMustChange,
)
