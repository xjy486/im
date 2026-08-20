package com.jitong.im.android

import android.content.Context
import com.google.gson.Gson
import com.jitong.im.android.auth.AuthApi
import com.jitong.im.android.auth.AuthRepository
import com.jitong.im.android.auth.InstallationIdentity
import com.jitong.im.android.auth.SessionAuthenticator
import com.jitong.im.android.auth.SessionInterceptor
import com.jitong.im.android.auth.SessionManager
import com.jitong.im.android.contact.ContactApi
import com.jitong.im.android.contact.ContactRepository
import com.jitong.im.android.local.AccountLocalStore
import com.jitong.im.android.message.MessageApi
import com.jitong.im.android.message.MessageRepository
import com.jitong.im.android.message.MessageWebSocket
import com.jitong.im.android.security.AccountKeyStore
import com.jitong.im.android.security.SecureSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID

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
    private val authenticatedContactApi = authenticatedRetrofit.create(ContactApi::class.java)
    private val authenticatedMessageApi = authenticatedRetrofit.create(MessageApi::class.java)
    private val authenticatedSyncApi = authenticatedRetrofit.create(com.jitong.im.android.message.SyncApi::class.java)
    private val messageWebSocket = MessageWebSocket(
        client = authenticatedClient,
        baseUrl = BuildConfig.BASE_URL,
        accessToken = { sessionStore.read()?.accessToken },
        gson = gson,
    )

    val authRepository = AuthRepository(
        authApi = rawAuthApi,
        authenticatedApi = authenticatedApi,
        sessionManager = sessionManager,
        installationIdentity = InstallationIdentity(appContext),
        gson = gson,
    )
    val sessionState = sessionManager.state
    val contactRepository = ContactRepository(authenticatedContactApi)
    val messageRepository = MessageRepository(
        api = authenticatedMessageApi,
        syncApi = authenticatedSyncApi,
        database = { localStore.activeDatabase() },
        webSocket = messageWebSocket,
        deviceId = { sessionManager.snapshot()?.deviceId?.let(UUID::fromString) },
    )

    private val messageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        messageScope.launch {
            sessionManager.state.collectLatest { state ->
                if (state is com.jitong.im.android.auth.SessionState.SignedIn) {
                    messageRepository.connect()
                    messageWebSocket.events.collect { event ->
                        val userId = sessionManager.snapshot()?.userId?.let(UUID::fromString) ?: return@collect
                        when (event.operation) {
                            "sync.ready" -> {
                                val watermark = event.body?.highWatermark ?: return@collect
                                messageRepository.synchronize(userId, watermark)
                            }
                            "message.created", "message.ack", "conversation.read" ->
                                messageRepository.apply(event, userId)
                        }
                    }
                } else {
                    messageRepository.disconnect()
                }
            }
        }
    }

    private fun retrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
}
