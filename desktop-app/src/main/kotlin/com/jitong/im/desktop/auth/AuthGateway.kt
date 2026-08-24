package com.jitong.im.desktop.auth

interface AuthGateway {
    fun login(accountNo: String, password: String, installationId: String): LoginResponse
    fun refresh(refreshToken: String): LoginResponse
    fun validate(accessToken: String)
    fun confirmReplacement(challenge: String): LoginResponse
    fun logout(accessToken: String)
    fun changePassword(
        accessToken: String,
        currentPassword: String,
        newPassword: String,
    ): LoginResponse
}
