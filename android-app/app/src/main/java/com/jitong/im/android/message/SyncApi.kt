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

    @GET("api/v1/users/{userId}/profile")
    suspend fun profile(
        @retrofit2.http.Path("userId") userId: UUID,
    ): Response<UserProfileResponse>

    @GET("api/v1/groups/{conversationId}/profile")
    suspend fun groupProfile(
        @retrofit2.http.Path("conversationId") conversationId: UUID,
    ): Response<GroupProfileResponse>

    @GET("api/v1/conversations/{conversationId}/read")
    suspend fun readStates(
        @retrofit2.http.Path("conversationId") conversationId: UUID,
    ): Response<ReadStatePageResponse>

    @POST("api/v1/conversations/{conversationId}/read")
    suspend fun markRead(
        @retrofit2.http.Path("conversationId") conversationId: UUID,
        @Body request: ReadStateRequest,
    ): Response<ReadStatePageResponse>
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
    val avatarUrl: String? = null,
    val avatarVersion: Long = 0,
    val avatarFallback: String = "?",
    val searchVisible: Boolean = true,
    val searchVisibleAfterSeq: Long = 0,
)

internal data class UserProfileResponse(
    val userId: UUID,
    val displayName: String,
    val avatarUrl: String?,
    val avatarVersion: Long,
    val avatarFallback: String,
)

internal data class GroupProfileResponse(
    val conversationId: UUID,
    val avatarUrl: String?,
    val avatarVersion: Long,
)

internal data class ReadStateRequest(
    val readSeq: Long,
)

internal data class ReadStatePageResponse(
    val version: Int,
    val conversationId: UUID,
    val states: List<ReadStateResponse>,
)

internal data class ReadStateResponse(
    val conversationId: UUID,
    val userId: UUID,
    val readSeq: Long,
)
