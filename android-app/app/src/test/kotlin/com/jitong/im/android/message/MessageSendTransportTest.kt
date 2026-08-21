package com.jitong.im.android.message

import kotlinx.coroutines.test.runTest
import retrofit2.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.util.UUID

class MessageSendTransportTest {
    private val conversationId = UUID.randomUUID()
    private val clientMsgId = UUID.randomUUID()

    @Test
    fun returns_the_authoritative_response_on_success() = runTest {
        val expected = response()
        val transport = ApiMessageSendTransport(
            object : MessageApi {
                override suspend fun send(
                    conversationId: UUID,
                    request: SendMessageRequest,
                ): Response<MessageResponse> = Response.success(expected)

                override suspend fun history(
                    conversationId: UUID,
                    afterSeq: Long,
                    limit: Int,
                ): Response<MessagePageResponse> = error("not used")

                override suspend fun recall(messageId: UUID): Response<MessageResponse> = error("not used")
            },
        )

        assertEquals(
            expected,
            transport.send(conversationId, clientMsgId, "hello"),
        )
    }

    @Test
    fun marks_server_failures_as_retryable_but_business_errors_as_manual_retry() = runTest {
        val retryable = ApiMessageSendTransport(apiReturning(Response.error<MessageResponse>(503, okhttp3.ResponseBody.create(null, ""))))
        val retryableFailure = assertFailsWith<MessageSendException> {
            retryable.send(conversationId, clientMsgId, "hello")
        }
        assertEquals(true, retryableFailure.retryable)

        val permanent = ApiMessageSendTransport(apiReturning(Response.error<MessageResponse>(403, okhttp3.ResponseBody.create(null, ""))))
        val permanentFailure = assertFailsWith<MessageSendException> {
            permanent.send(conversationId, clientMsgId, "hello")
        }
        assertEquals(false, permanentFailure.retryable)
    }

    private fun apiReturning(response: Response<MessageResponse>) = object : MessageApi {
        override suspend fun send(
            conversationId: UUID,
            request: SendMessageRequest,
        ): Response<MessageResponse> = response

        override suspend fun history(
            conversationId: UUID,
            afterSeq: Long,
            limit: Int,
        ): Response<MessagePageResponse> = error("not used")

        override suspend fun recall(messageId: UUID): Response<MessageResponse> = error("not used")
    }

    private fun response() = MessageResponse(
        messageId = UUID.randomUUID(),
        conversationId = conversationId,
        senderId = UUID.randomUUID(),
        clientMsgId = clientMsgId,
        conversationSeq = 1,
        type = "TEXT",
        state = "ACTIVE",
        text = "hello",
        serverAcceptedAt = "2026-08-20T00:00:00Z",
    )
}
