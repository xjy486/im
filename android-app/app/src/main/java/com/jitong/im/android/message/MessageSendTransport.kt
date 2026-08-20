package com.jitong.im.android.message

import java.io.IOException
import java.util.UUID

internal interface MessageSendTransport {
    suspend fun send(conversationId: UUID, clientMsgId: UUID, text: String): MessageResponse
}

internal class ApiMessageSendTransport(
    private val api: MessageApi,
) : MessageSendTransport {
    override suspend fun send(
        conversationId: UUID,
        clientMsgId: UUID,
        text: String,
    ): MessageResponse {
        val response = api.send(
            conversationId,
            SendMessageRequest(clientMsgId, text),
        )
        if (response.isSuccessful && response.body() != null) {
            return response.body()!!
        }
        throw MessageSendException(
            retryable = response.code() >= 500 || response.code() == 408,
            message = "Message send failed with HTTP ${response.code()}",
        )
    }
}
