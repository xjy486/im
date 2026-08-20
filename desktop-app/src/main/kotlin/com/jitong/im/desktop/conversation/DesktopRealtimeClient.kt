package com.jitong.im.desktop.conversation

import com.jitong.im.desktop.local.LocalDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class DesktopRealtimeClient(
    private val baseUrl: String,
    private val accessToken: () -> String?,
    private val onEnvelope: (DesktopRealtimeEnvelope) -> Unit,
    private val onConnectionState: (Boolean) -> Unit,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) : AutoCloseable {
    private val reconnectDelay = 1_000L
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var enabled = false

    @Synchronized
    fun connect() {
        enabled = true
        reconnectJob?.cancel()
        reconnectJob = null
        if (socket == null) {
            open()
        }
    }

    @Synchronized
    fun reconnect() {
        if (!enabled) return
        socket?.close(1000, "refresh")
        socket = null
        open()
    }

    @Synchronized
    fun stopForAuthenticationFailure() {
        enabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.cancel()
        socket = null
        onConnectionState(false)
    }

    @Synchronized
    fun disconnect() {
        enabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "disconnect")
        socket = null
        onConnectionState(false)
    }

    fun send(
        conversationId: String,
        clientMsgId: String,
        text: String,
    ): Boolean {
        val envelope = DesktopRealtimeEnvelope(
            version = 1,
            operation = "message.send",
            requestId = java.util.UUID.randomUUID().toString(),
            body = DesktopRealtimeBody(
                conversationId = conversationId,
                clientMsgId = clientMsgId,
                text = text))
        return synchronized(this) {
            socket?.send(JsonSupport.encode(envelope)) == true
        }
    }

    override fun close() {
        disconnect()
    }

    private fun open() {
        if (!enabled || socket != null) return
        val url = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/api/v1/ws"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${accessToken().orEmpty()}")
            .build()
        socket = httpClient.newBuilder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
            .newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@DesktopRealtimeClient) {
                if (socket !== webSocket) return
            }
            onConnectionState(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { JsonSupport.decode(text) }
                .onSuccess(onEnvelope)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onConnectionState(false)
            if (response?.code == 401 || response?.code == 403) {
                stopForAuthenticationFailure()
            } else {
                scheduleReconnect(webSocket)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onConnectionState(false)
            if (code == 1008 || code == 4001 || code == 4003) {
                stopForAuthenticationFailure()
            } else {
                scheduleReconnect(webSocket)
            }
        }
    }

    private fun scheduleReconnect(closedSocket: WebSocket) {
        synchronized(this) {
            if (socket === closedSocket) {
                socket = null
            }
            if (!enabled || reconnectJob?.isActive == true) return
            reconnectJob = scope.launch {
                delay(reconnectDelay)
                synchronized(this@DesktopRealtimeClient) {
                    reconnectJob = null
                    open()
                }
            }
        }
    }
}

private object JsonSupport {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(value: DesktopRealtimeEnvelope): String = json.encodeToString(value)

    fun decode(value: String): DesktopRealtimeEnvelope = json.decodeFromString(value)
}
