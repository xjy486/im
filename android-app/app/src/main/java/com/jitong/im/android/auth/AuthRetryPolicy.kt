package com.jitong.im.android.auth

internal object AuthRetryPolicy {
    fun mayRefresh(responseCount: Int, failedAccessToken: String?, currentAccessToken: String?): Boolean =
        responseCount < 2
            && !failedAccessToken.isNullOrBlank()
            && !currentAccessToken.isNullOrBlank()
            && failedAccessToken == currentAccessToken
}
