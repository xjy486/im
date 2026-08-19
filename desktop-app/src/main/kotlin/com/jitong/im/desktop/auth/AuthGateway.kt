package com.jitong.im.desktop.auth

interface AuthGateway {
    fun login(accountNo: String, password: String, installationId: String): LoginResponse
    fun refresh(refreshToken: String): LoginResponse
    fun validate(accessToken: String)
    fun confirmReplacement(challenge: String): LoginResponse
}
