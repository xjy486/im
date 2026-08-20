package com.jitong.im.android.message

import java.io.IOException
import java.util.UUID

internal interface MessageSendTransport {
    suspend fun send(conversationId: UUID, clientMsgId: UUID, text: String): MessageResponse

    suspend fun sendImage(
        conversationId: UUID,
        clientMsgId: UUID,
        mediaId: UUID,
    ): MessageResponse = error("Image transport is not configured")
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
            SendMessageRequest(clientMsgId = clientMsgId, text = text),
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
