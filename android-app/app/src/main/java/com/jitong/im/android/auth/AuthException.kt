package com.jitong.im.android.auth

open class AuthException(
    val statusCode: Int,
    val code: String,
    override val message: String,
) : RuntimeException(message)

class DeviceReplacementRequiredException(
    val challenge: String,
    val deviceClass: String,
    message: String = "This MOBILE device must replace the existing device",
) : AuthException(409, "DEVICE_REPLACEMENT_REQUIRED", message)

class AuthenticationInvalidException(
    statusCode: Int = 401,
    code: String = "AUTH_INVALID",
    message: String = "Authentication is invalid",
) : AuthException(statusCode, code, message)
