package com.jitong.im.android.message

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.UUID

internal interface MessageApi {
    @POST("api/v1/conversations/{conversationId}/messages")
    suspend fun send(
        @Path("conversationId") conversationId: UUID,
        @Body request: SendMessageRequest,
    ): Response<MessageResponse>

    @GET("api/v1/conversations/{conversationId}/messages")
    suspend fun history(
        @Path("conversationId") conversationId: UUID,
        @Query("afterSeq") afterSeq: Long = 0,
        @Query("limit") limit: Int = 200,
    ): Response<MessagePageResponse>

}

internal data class SendMessageRequest(
    val clientMsgId: UUID,
    val text: String,
)

internal data class MessageResponse(
    val messageId: UUID,
    val conversationId: UUID,
    val senderId: UUID,
    val clientMsgId: UUID,
    val conversationSeq: Long,
    val type: String,
    val state: String,
    val text: String,
    val serverAcceptedAt: String,
)

internal data class MessagePageResponse(
    val version: Int,
    val conversationId: UUID,
    val messages: List<MessageResponse>,
)
