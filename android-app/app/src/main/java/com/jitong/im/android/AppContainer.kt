package com.jitong.im.android

import android.content.Context
import com.google.gson.Gson
import com.jitong.im.android.auth.AuthApi
import com.jitong.im.android.auth.AuthRepository
import com.jitong.im.android.auth.InstallationIdentity
import com.jitong.im.android.auth.SessionAuthenticator
import com.jitong.im.android.auth.SessionInterceptor
import com.jitong.im.android.auth.SessionManager
import com.jitong.im.android.local.AccountLocalStore
import com.jitong.im.android.security.AccountKeyStore
import com.jitong.im.android.security.SecureSessionStore
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

internal class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val sessionStore = SecureSessionStore(appContext)
    private val accountKeyStore = AccountKeyStore(appContext)
    private val localStore = AccountLocalStore(appContext, accountKeyStore)
    private val rawClient = OkHttpClient.Builder().build()
    private val rawRetrofit = retrofit(rawClient)
    private val rawAuthApi = rawRetrofit.create(AuthApi::class.java)
    private val sessionManager = SessionManager(rawAuthApi, sessionStore, localStore)
    private val authenticatedClient = rawClient.newBuilder()
        .addInterceptor(SessionInterceptor(sessionStore))
        .authenticator(SessionAuthenticator(sessionManager))
        .build()
    private val authenticatedRetrofit = retrofit(authenticatedClient)
    private val authenticatedApi = authenticatedRetrofit.create(AuthApi::class.java)

    val authRepository = AuthRepository(
        authApi = rawAuthApi,
        authenticatedApi = authenticatedApi,
        sessionManager = sessionManager,
        installationIdentity = InstallationIdentity(appContext),
        gson = gson,
    )
    val sessionState = sessionManager.state

    private fun retrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
}
