package com.jitong.im.android.message

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.UUID

internal interface SyncApi {
    @GET("api/v1/sync")
    suspend fun page(
        @Query("after") afterSeq: Long,
        @Query("until") untilSeq: Long?,
        @Query("limit") limit: Int = 200,
    ): Response<SyncPageResponse>

    @POST("api/v1/sync/ack")
    suspend fun acknowledge(@Body request: SyncAckRequest): Response<SyncAckResponse>

    @GET("api/v1/conversations")
    suspend fun conversations(): Response<List<SyncConversationResponse>>
}

internal data class SyncPageResponse(
    val version: Int,
    val afterSeq: Long,
    val highWatermark: Long,
    val untilSeq: Long,
    val nextAfterSeq: Long,
    val hasMore: Boolean,
    val events: List<SyncEventResponse>,
)

internal data class SyncEventResponse(
    val syncSeq: Long,
    val eventType: String,
    val entityId: UUID,
    val conversationId: UUID?,
    val createdAt: String,
)

internal data class SyncAckRequest(
    val syncSeq: Long,
)

internal data class SyncAckResponse(
    val version: Int,
    val deviceId: UUID,
    val ackedSeq: Long,
)

internal data class SyncConversationResponse(
    val version: Int,
    val conversationId: UUID,
    val peerUserId: UUID,
    val peerAccountNo: String,
    val peerDisplayName: String,
    val status: String,
    val relationship: String,
    val blockedByMe: Boolean,
)
