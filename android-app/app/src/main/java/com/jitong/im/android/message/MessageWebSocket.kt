package com.jitong.im.android.message

import com.google.gson.Gson
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class MessageWebSocket(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val accessToken: () -> String?,
    private val gson: Gson,
) {
    private var socket: WebSocket? = null
    private val _events = MutableSharedFlow<WireEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<WireEvent> = _events

    fun connect() {
        disconnect()
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

    fun send(conversationId: UUID, clientMsgId: UUID, text: String): Boolean {
        val requestId = UUID.randomUUID()
        return socket?.send(
            gson.toJson(
                WireEnvelope(
                    version = 1,
                    operation = "message.send",
                    requestId = requestId,
                    body = SendBody(conversationId, clientMsgId, text),
                ),
            ),
        ) ?: false
    }

    fun disconnect() {
        socket?.close(1000, "logout")
        socket = null
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { gson.fromJson(text, WireEvent::class.java) }
                .onSuccess { _events.tryEmit(it) }
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
    val text: String,
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
    val serverAcceptedAt: String?,
)
