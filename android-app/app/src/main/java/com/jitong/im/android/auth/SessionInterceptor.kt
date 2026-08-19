package com.jitong.im.android.auth

import com.jitong.im.android.security.SecureSessionStore
import okhttp3.Interceptor
import okhttp3.Response

class SessionInterceptor(
    private val sessionStore: SecureSessionStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val session = sessionStore.read()
        val request = if (session == null) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer ${session.accessToken}")
                .build()
        }
        return chain.proceed(request)
    }
}
