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

    @POST("api/v1/messages/{messageId}/recall")
    suspend fun recall(@retrofit2.http.Path("messageId") messageId: UUID): Response<MessageResponse>
}

internal data class SendMessageRequest(
    val clientMsgId: UUID,
    val type: String = "TEXT",
    val text: String? = null,
    val mediaId: UUID? = null,
)

internal data class MessageResponse(
    val messageId: UUID,
    val conversationId: UUID,
    val senderId: UUID,
    val senderDisplayName: String = "",
    val clientMsgId: UUID,
    val conversationSeq: Long,
    val type: String,
    val state: String,
    val text: String?,
    val mediaId: UUID? = null,
    val serverAcceptedAt: String,
    val recalledAt: String? = null,
    val systemEventType: String? = null,
    val systemTargetUserId: UUID? = null,
    val systemRole: String? = null,
    val moderatedByUserId: UUID? = null,
    val moderatedReason: String? = null,
    val moderatedAt: String? = null,
)

internal data class MessagePageResponse(
    val version: Int,
    val conversationId: UUID,
    val messages: List<MessageResponse>,
)
