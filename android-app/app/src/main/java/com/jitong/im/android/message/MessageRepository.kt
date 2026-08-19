package com.jitong.im.android.message

import com.jitong.im.android.local.AccountDatabase
import com.jitong.im.android.local.LocalMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class MessageRepository(
    private val api: MessageApi,
    private val database: () -> AccountDatabase?,
    private val webSocket: MessageWebSocket,
) {
    private val pendingAcks = ConcurrentHashMap<UUID, CompletableDeferred<MessageResponse>>()

    fun observe(conversationId: UUID): Flow<List<LocalMessageEntity>> =
        database()?.messageDao()?.observe(conversationId.toString())
            ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun loadHistory(conversationId: UUID) {
        val db = database() ?: return
        // The authoritative sequence is supplied by the server. The local SQL
        // query is intentionally not used as an ordering source for transport.
        val response = api.history(conversationId).bodyOrThrow()
        withContext(Dispatchers.IO) {
            val dao = db.messageDao()
            response.messages.forEach {
                dao.deleteByClientMsgId(it.clientMsgId.toString())
                dao.upsert(it.toEntity())
            }
        }
    }

    suspend fun send(conversationId: UUID, text: String): UUID {
        require(text.isNotBlank()) { "Message text must not be blank" }
        val db = database() ?: throw IOException("No local account database is open")
        val clientMsgId = UUID.randomUUID()
        withContext(Dispatchers.IO) {
            db.messageDao().upsert(
                LocalMessageEntity(
                    messageId = "local:$clientMsgId",
                    conversationId = conversationId.toString(),
                    senderId = "local",
                    clientMsgId = clientMsgId.toString(),
                    conversationSeq = null,
                    type = "TEXT",
                    state = "ACTIVE",
                    localState = "SENDING",
                    text = text,
                    serverAcceptedAt = null,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        val acknowledgement = CompletableDeferred<MessageResponse>()
        pendingAcks[clientMsgId] = acknowledgement
        if (!webSocket.send(conversationId, clientMsgId, text)) {
            val response = api.send(conversationId, SendMessageRequest(clientMsgId, text))
            if (!response.isSuccessful || response.body() == null) {
                pendingAcks.remove(clientMsgId)
                throw IOException("Message send failed with HTTP ${response.code()}")
            }
            upsertAccepted(response.body()!!)
            pendingAcks.remove(clientMsgId)
        } else {
            val accepted = withTimeoutOrNull(10_000) { acknowledgement.await() }
            pendingAcks.remove(clientMsgId)
            if (accepted == null) {
                val response = api.send(conversationId, SendMessageRequest(clientMsgId, text))
                if (!response.isSuccessful || response.body() == null) {
                    throw IOException("Message send failed with HTTP ${response.code()}")
                }
                upsertAccepted(response.body()!!)
            }
        }
        return clientMsgId
    }

    fun connect() {
        webSocket.connect()
    }

    fun disconnect() {
        webSocket.disconnect()
    }

    suspend fun apply(event: WireEvent, currentUserId: UUID) {
        val body = event.body ?: return
        val messageId = body.messageId ?: return
        val conversationId = body.conversationId ?: return
        val senderId = body.senderId ?: return
        val clientMsgId = body.clientMsgId ?: return
        val db = database() ?: return
        withContext(Dispatchers.IO) {
            val dao = db.messageDao()
            val existing = dao.findByClientMsgId(clientMsgId.toString())
            dao.deleteByClientMsgId(clientMsgId.toString())
            dao.upsert(
                LocalMessageEntity(
                    messageId = messageId.toString(),
                    conversationId = conversationId.toString(),
                    senderId = senderId.toString(),
                    clientMsgId = clientMsgId.toString(),
                    conversationSeq = body.conversationSeq,
                    type = body.type ?: "TEXT",
                    state = body.state ?: "ACTIVE",
                    localState = if (senderId == currentUserId) "SENT" else "RECEIVED",
                    text = body.text.orEmpty(),
                    serverAcceptedAt = body.serverAcceptedAt,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                ),
            )
        }
        pendingAcks[clientMsgId]?.complete(
            MessageResponse(
                messageId = messageId,
                conversationId = conversationId,
                senderId = senderId,
                clientMsgId = clientMsgId,
                conversationSeq = body.conversationSeq ?: 0,
                type = body.type ?: "TEXT",
                state = body.state ?: "ACTIVE",
                text = body.text.orEmpty(),
                serverAcceptedAt = body.serverAcceptedAt.orEmpty(),
            ),
        )
    }

    private suspend fun upsertAccepted(response: MessageResponse) {
        val dao = database()?.messageDao() ?: return
        withContext(Dispatchers.IO) {
            dao.deleteByClientMsgId(response.clientMsgId.toString())
            dao.upsert(response.toEntity(localState = "SENT"))
        }
    }

    private fun MessageResponse.toEntity(localState: String = "SENT") = LocalMessageEntity(
        messageId = messageId.toString(),
        conversationId = conversationId.toString(),
        senderId = senderId.toString(),
        clientMsgId = clientMsgId.toString(),
        conversationSeq = conversationSeq,
        type = type,
        state = state,
        localState = localState,
        text = text,
        serverAcceptedAt = serverAcceptedAt,
        createdAt = System.currentTimeMillis(),
    )

    private fun <T> Response<T>.bodyOrThrow(): T {
        if (isSuccessful && body() != null) return body()!!
        throw IOException("Message request failed with HTTP ${code()}")
    }
}
