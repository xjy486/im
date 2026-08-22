package com.jitong.im.android.message

import com.google.gson.Gson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class MessageWebSocket(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val accessToken: () -> String?,
    private val gson: Gson,
) {
    private var socket: WebSocket? = null
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor()
    private var reconnectEnabled = false
    private var reconnectScheduled = false
    private var reconnectDelayMillis = 1_000L
    private val eventChannel = Channel<WireEvent>(Channel.UNLIMITED)
    val events: Flow<WireEvent> = eventChannel.receiveAsFlow()

    @Synchronized
    fun connect() {
        reconnectEnabled = true
        reconnectScheduled = false
        reconnectDelayMillis = 1_000L
        closeSocket()
        openSocket()
    }

    @Synchronized
    fun disconnect() {
        reconnectEnabled = false
        reconnectScheduled = false
        closeSocket()
    }

    @Synchronized
    fun send(conversationId: UUID, clientMsgId: UUID, text: String): Boolean {
        return send(
            conversationId = conversationId,
            clientMsgId = clientMsgId,
            type = "TEXT",
            text = text,
            mediaId = null,
        )
    }

    @Synchronized
    fun sendImage(conversationId: UUID, clientMsgId: UUID, mediaId: UUID): Boolean {
        return send(
            conversationId = conversationId,
            clientMsgId = clientMsgId,
            type = "IMAGE",
            text = null,
            mediaId = mediaId,
        )
    }

    private fun send(
        conversationId: UUID,
        clientMsgId: UUID,
        type: String,
        text: String?,
        mediaId: UUID?,
    ): Boolean {
        val requestId = UUID.randomUUID()
        return socket?.send(
            gson.toJson(
                WireEnvelope(
                    version = 1,
                    operation = "message.send",
                    requestId = requestId,
                    body = SendBody(
                        conversationId = conversationId,
                        clientMsgId = clientMsgId,
                        type = type,
                        text = text,
                        mediaId = mediaId,
                    ),
                ),
            ),
        ) ?: false
    }

    @Synchronized
    fun isConnected(): Boolean = socket != null

    private fun closeSocket() {
        val current = socket
        socket = null
        current?.close(1000, "reconnect")
    }

    private fun openSocket() {
        if (!reconnectEnabled || socket != null) {
            return
        }
        val url = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/api/v1/ws"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${accessToken().orEmpty()}")
            .build()
        socket = client.newBuilder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
            .newWebSocket(request, listener)
    }

    private fun onConnected(webSocket: WebSocket) {
        synchronized(this) {
            if (socket !== webSocket) {
                return
            }
            reconnectDelayMillis = 1_000L
            reconnectScheduled = false
        }
    }

    private fun onDisconnected(webSocket: WebSocket) {
        synchronized(this) {
            if (socket === webSocket) {
                socket = null
            }
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delay = synchronized(this) {
            if (!reconnectEnabled || reconnectScheduled) {
                return
            }
            reconnectScheduled = true
            val current = reconnectDelayMillis
            reconnectDelayMillis = (reconnectDelayMillis * 2).coerceAtMost(30_000L)
            current
        }
        reconnectExecutor.schedule(
            {
                synchronized(this) {
                    reconnectScheduled = false
                    if (!reconnectEnabled || socket != null) {
                        return@schedule
                    }
                }
                synchronized(this) {
                    openSocket()
                }
            },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onConnected(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { gson.fromJson(text, WireEvent::class.java) }
                .onSuccess { eventChannel.trySend(it) }
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            onDisconnected(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDisconnected(webSocket)
        }
    }
}

private data class WireEnvelope(
    val version: Int,
    val operation: String,
    val requestId: UUID,
    val body: SendBody,
)

private data class SendBody(
    val conversationId: UUID,
    val clientMsgId: UUID,
    val type: String,
    val text: String?,
    val mediaId: UUID?,
)

internal data class WireEvent(
    val version: Int?,
    val operation: String?,
    val requestId: UUID?,
    val body: WireMessageBody?,
)

internal data class WireMessageBody(
    val messageId: UUID?,
    val conversationId: UUID?,
    val senderId: UUID?,
    val clientMsgId: UUID?,
    val conversationSeq: Long?,
    val type: String?,
    val state: String?,
    val text: String?,
    val mediaId: UUID?,
    val serverAcceptedAt: String?,
    val recalledAt: String?,
    val systemEventType: String? = null,
    val systemTargetUserId: UUID? = null,
    val systemRole: String? = null,
    val moderatedByUserId: UUID? = null,
    val moderatedReason: String? = null,
    val moderatedAt: String? = null,
    val syncSeq: Long?,
    val deviceId: UUID?,
    val deviceClass: String?,
    val highWatermark: Long?,
    val userId: UUID?,
    val readSeq: Long?,
    val displayName: String?,
    val avatarUrl: String?,
    val avatarVersion: Long?,
    val avatarFallback: String?,
    val code: String?,
    val message: String?,
)
