package com.jitong.im.android

import android.content.Context
import com.google.gson.Gson
import com.google.firebase.messaging.FirebaseMessaging
import com.jitong.im.android.auth.AuthApi
import com.jitong.im.android.ai.AiApi
import com.jitong.im.android.ai.AiRepository
import com.jitong.im.android.auth.AuthRepository
import com.jitong.im.android.auth.InstallationIdentity
import com.jitong.im.android.auth.SessionAuthenticator
import com.jitong.im.android.auth.SessionInterceptor
import com.jitong.im.android.auth.SessionManager
import com.jitong.im.android.contact.ContactApi
import com.jitong.im.android.contact.ContactRepository
import com.jitong.im.android.group.GroupApi
import com.jitong.im.android.group.GroupRepository
import com.jitong.im.android.local.AccountLocalStore
import com.jitong.im.android.media.MediaApi
import com.jitong.im.android.media.AvatarRepository
import com.jitong.im.android.message.MessageApi
import com.jitong.im.android.message.PendingMessageScheduler
import com.jitong.im.android.message.MessageRepository
import com.jitong.im.android.message.MessageWebSocket
import com.jitong.im.android.message.SyncReadyHandler
import com.jitong.im.android.push.PushTokenApi
import com.jitong.im.android.push.PushTokenRepository
import com.jitong.im.android.push.PushTokenRegistrationScheduler
import com.jitong.im.android.security.AccountKeyStore
import com.jitong.im.android.security.SecureSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
    private val authenticatedGroupApi = authenticatedRetrofit.create(GroupApi::class.java)
    private val authenticatedMessageApi = authenticatedRetrofit.create(MessageApi::class.java)
    private val authenticatedAiApi = authenticatedRetrofit.create(AiApi::class.java)
    private val authenticatedMediaApi = authenticatedRetrofit.create(MediaApi::class.java)
    private val authenticatedSyncApi = authenticatedRetrofit.create(com.jitong.im.android.message.SyncApi::class.java)
    private val authenticatedPushTokenApi = authenticatedRetrofit.create(PushTokenApi::class.java)
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
    val avatarRepository = AvatarRepository(
        authenticatedMediaApi,
        { sessionManager.snapshot()?.let { UUID.fromString(it.userId) } },
        { localStore.activeMediaCache() },
    )
    val groupRepository = GroupRepository(authenticatedGroupApi, avatarRepository)
    val pushTokenRepository = PushTokenRepository(authenticatedPushTokenApi)
    val messageRepository = MessageRepository(
        api = authenticatedMessageApi,
        syncApi = authenticatedSyncApi,
        database = { localStore.activeDatabase() },
        webSocket = messageWebSocket,
        deviceId = { sessionManager.snapshot()?.deviceId?.let(UUID::fromString) },
        mediaApi = authenticatedMediaApi,
        mediaCache = { localStore.activeMediaCache() },
    )
    val aiRepository = AiRepository(
        api = authenticatedAiApi,
        database = { localStore.activeDatabase() },
    )
    fun sessionSnapshot() = sessionManager.snapshot()
    private var notificationSyncPending = false

    private val messageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncReadyHandler = SyncReadyHandler(
        synchronize = messageRepository::synchronize,
        onFailure = { PendingMessageScheduler.enqueue(appContext) },
    )

    init {
        messageRepository.setPendingSendScheduler {
            PendingMessageScheduler.enqueue(appContext)
        }
        sessionManager.setBeforeLogout {
            messageRepository.prepareForLogout()
        }
        messageScope.launch {
            sessionManager.state.collectLatest { state ->
                if (state is com.jitong.im.android.auth.SessionState.SignedIn) {
                    messageRepository.enableAutomaticSending()
                    messageRepository.connect()
                    PendingMessageScheduler.enqueue(appContext)
                    if (notificationSyncPending) {
                        syncAfterNotification()
                    }
                    scheduleCurrentPushTokenRegistration()
                    messageWebSocket.events.collect { event ->
                        val userId = sessionManager.snapshot()?.userId?.let(UUID::fromString) ?: return@collect
                        when (event.operation) {
                            "sync.ready" -> {
                                val watermark = event.body?.highWatermark ?: return@collect
                                syncReadyHandler.handle(userId, watermark)
                            }
                            "message.created", "message.ack", "message.recalled", "message.moderated", "conversation.read",
                            "user.profile.updated", "group.profile.updated",
                            "membership.revoked", "membership.granted", "group.dissolved",
                            "contact.relationship.changed", "error" ->
                                runCatching { messageRepository.apply(event, userId) }
                                    .onFailure { exception ->
                                        if (exception is CancellationException) throw exception
                                        PendingMessageScheduler.enqueue(appContext)
                                    }
                            "ai.job.updated", "ai.artifact.deleted", "ai.job.deleted",
                            "ai.action-item.updated", "ai.action-item.deleted" ->
                                aiRepository.refresh()
                        }
                    }
                } else {
                    messageRepository.disableAutomaticSending()
                    messageRepository.disconnect()
                    if (state is com.jitong.im.android.auth.SessionState.SignedOut) {
                        PendingMessageScheduler.cancel(appContext)
                        PushTokenRegistrationScheduler.cancel(appContext)
                    }
                }
            }
        }
    }

    suspend fun restoreSessionForWorker(): Boolean {
        authRepository.restore()
        val signedIn = sessionManager.state.value is com.jitong.im.android.auth.SessionState.SignedIn
        if (signedIn) {
            messageRepository.enableAutomaticSending()
        }
        return signedIn
    }

    suspend fun syncLatestForWorker() {
        val userId = sessionManager.snapshot()?.userId?.let(UUID::fromString) ?: return
        messageRepository.syncLatest(userId)
    }

    fun handleNotification(type: String) {
        notificationSyncPending = true
        if (sessionManager.state.value is com.jitong.im.android.auth.SessionState.SignedIn) {
            syncAfterNotification()
        }
        PendingMessageScheduler.enqueue(appContext)
    }

    private fun syncAfterNotification() {
        val userId = sessionManager.snapshot()?.userId?.let(UUID::fromString) ?: return
        messageScope.launch {
            runCatching { messageRepository.syncLatest(userId) }
                .onSuccess { notificationSyncPending = false }
                .onFailure { PendingMessageScheduler.enqueue(appContext) }
        }
    }

    internal suspend fun registerCurrentPushTokenForWorker(): Boolean {
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull()
            ?: return true
        val token = try {
            messaging.token.await()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return false
        }
        return registerPushToken(token, System.currentTimeMillis())
    }

    internal suspend fun registerPushToken(token: String, tokenVersion: Long): Boolean = try {
            pushTokenRepository.register(token, tokenVersion)
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }

    private fun scheduleCurrentPushTokenRegistration() {
        PushTokenRegistrationScheduler.enqueue(appContext)
    }

    private fun retrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
}
