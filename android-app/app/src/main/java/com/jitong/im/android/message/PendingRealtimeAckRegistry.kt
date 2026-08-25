package com.jitong.im.android.message

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class PendingRealtimeAckRegistry {
    private val pendingByClientMessage = ConcurrentHashMap<UUID, CompletableDeferred<MessageResponse>>()
    private val clientMessageByRequest = ConcurrentHashMap<UUID, UUID>()

    fun register(
        clientMsgId: UUID,
        requestId: UUID,
        deferred: CompletableDeferred<MessageResponse>,
    ) {
        pendingByClientMessage[clientMsgId] = deferred
        clientMessageByRequest[requestId] = clientMsgId
    }

    fun fail(requestId: UUID, code: String?, message: String?) {
        val clientMsgId = clientMessageByRequest.remove(requestId) ?: return
        val deferred = pendingByClientMessage.remove(clientMsgId) ?: return
        deferred.completeExceptionally(
            MessageSendException(
                retryable = code == "INVALID_REQUEST",
                code = code,
                message = message ?: "Realtime message command failed",
            ),
        )
    }

    fun remove(clientMsgId: UUID) {
        pendingByClientMessage.remove(clientMsgId)
        clientMessageByRequest.entries.removeIf { it.value == clientMsgId }
    }

    fun complete(clientMsgId: UUID, response: MessageResponse) {
        pendingByClientMessage.remove(clientMsgId)?.complete(response)
        clientMessageByRequest.entries.removeIf { it.value == clientMsgId }
    }
}
