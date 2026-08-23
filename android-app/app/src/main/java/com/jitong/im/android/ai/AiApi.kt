package com.jitong.im.android.ai

import com.google.gson.JsonElement
import com.jitong.im.android.message.AiActionItemResponse
import com.jitong.im.android.message.AiArtifactResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.UUID

internal interface AiApi {
    @GET("api/v1/conversations/{conversationId}/ai/consent")
    suspend fun consent(@Path("conversationId") conversationId: UUID): Response<AiConsentResponse>

    @PATCH("api/v1/conversations/{conversationId}/ai/consent")
    suspend fun updateConsent(
        @Path("conversationId") conversationId: UUID,
        @Body request: AiConsentUpdate,
    ): Response<AiConsentResponse>

    @POST("api/v1/conversations/{conversationId}/ai/smart-replies")
    suspend fun smartReplies(
        @Path("conversationId") conversationId: UUID,
        @Body request: AiRequest,
    ): Response<AiJobResponse>

    @POST("api/v1/conversations/{conversationId}/ai/extract")
    suspend fun extract(
        @Path("conversationId") conversationId: UUID,
        @Body request: AiExtractionRequest,
    ): Response<AiJobResponse>

    @GET("api/v1/ai/jobs/{jobId}")
    suspend fun job(@Path("jobId") jobId: UUID): Response<AiJobResponse>

    @GET("api/v1/ai/artifacts")
    suspend fun artifacts(): Response<List<AiArtifactResponse>>

    @DELETE("api/v1/ai/artifacts/{artifactId}")
    suspend fun deleteArtifact(@Path("artifactId") artifactId: UUID): Response<Unit>

    @GET("api/v1/ai/action-items")
    suspend fun actionItems(): Response<List<AiActionItemResponse>>

    @PATCH("api/v1/ai/action-items/{actionItemId}")
    suspend fun updateActionItem(
        @Path("actionItemId") actionItemId: UUID,
        @Body request: AiActionItemUpdate,
    ): Response<AiActionItemResponse>

    @DELETE("api/v1/ai/action-items/{actionItemId}")
    suspend fun deleteActionItem(@Path("actionItemId") actionItemId: UUID): Response<Unit>
}

internal data class AiConsentUpdate(val enabled: Boolean)
internal data class AiRequest(val requestId: UUID = UUID.randomUUID())
internal data class AiExtractionRequest(
    val requestId: UUID = UUID.randomUUID(),
    val messageIds: List<UUID>,
)
internal data class AiActionItemUpdate(val status: String)

internal data class AiConsentResponse(
    val version: Int,
    val conversationId: UUID,
    val userId: UUID,
    val enabled: Boolean,
    val enabledForBoth: Boolean,
    val policyVersion: Long,
)

internal data class AiJobResponse(
    val version: Int,
    val jobId: UUID,
    val conversationId: UUID,
    val kind: String,
    val status: String,
    val errorCode: String?,
    val result: JsonElement?,
)

internal data class AiDraft(val text: String, val tone: String)
