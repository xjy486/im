package com.jitong.im.android.group

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.UUID

internal interface GroupApi {
    @POST("api/v1/groups")
    suspend fun create(@Body request: CreateGroupRequest): Response<GroupCreateResponse>

    @GET("api/v1/groups")
    suspend fun list(): Response<List<GroupSummary>>

    @POST("api/v1/groups/{conversationId}/leave")
    suspend fun leave(@Path("conversationId") conversationId: UUID): Response<Unit>

    @GET("api/v1/groups/search")
    suspend fun search(@Query("query") query: String): Response<GroupSearchPage>

    @POST("api/v1/groups/{conversationId}/invites")
    suspend fun createInvite(
        @Path("conversationId") conversationId: UUID,
        @Body request: GroupInviteCreateRequest?,
    ): Response<GroupInviteResponse>

    @GET("api/v1/groups/invites/resolve")
    suspend fun resolveInvite(@Query("token") token: String): Response<GroupInviteResolveResponse>

    @DELETE("api/v1/groups/{conversationId}/invites/{inviteId}")
    suspend fun revokeInvite(
        @Path("conversationId") conversationId: UUID,
        @Path("inviteId") inviteId: UUID,
    ): Response<Unit>

    @POST("api/v1/groups/{conversationId}/join-requests")
    suspend fun createJoinRequest(
        @Path("conversationId") conversationId: UUID,
        @Body request: GroupJoinRequestCreateRequest?,
    ): Response<GroupJoinRequestResponse>

    @GET("api/v1/groups/{conversationId}/join-requests")
    suspend fun listJoinRequests(
        @Path("conversationId") conversationId: UUID,
    ): Response<List<GroupJoinRequestSummary>>

    @POST("api/v1/groups/{conversationId}/join-requests/{requestId}/approve")
    suspend fun approveJoinRequest(
        @Path("conversationId") conversationId: UUID,
        @Path("requestId") requestId: UUID,
    ): Response<GroupJoinRequestResponse>

    @POST("api/v1/groups/{conversationId}/join-requests/{requestId}/reject")
    suspend fun rejectJoinRequest(
        @Path("conversationId") conversationId: UUID,
        @Path("requestId") requestId: UUID,
    ): Response<GroupJoinRequestResponse>

    @POST("api/v1/groups/{conversationId}/join-requests/{requestId}/cancel")
    suspend fun cancelJoinRequest(
        @Path("conversationId") conversationId: UUID,
        @Path("requestId") requestId: UUID,
    ): Response<GroupJoinRequestResponse>

    @DELETE("api/v1/groups/{conversationId}/members/{userId}")
    suspend fun removeMember(
        @Path("conversationId") conversationId: UUID,
        @Path("userId") userId: UUID,
    ): Response<Unit>

    @POST("api/v1/groups/{conversationId}/members")
    suspend fun addMember(
        @Path("conversationId") conversationId: UUID,
        @Body request: GroupMemberAddRequest,
    ): Response<GroupMemberAddResponse>

    @POST("api/v1/groups/{conversationId}/bans/{userId}")
    suspend fun banUser(
        @Path("conversationId") conversationId: UUID,
        @Path("userId") userId: UUID,
        @Body request: GroupBanRequest?,
    ): Response<Unit>

    @DELETE("api/v1/groups/{conversationId}/bans/{userId}")
    suspend fun unbanUser(
        @Path("conversationId") conversationId: UUID,
        @Path("userId") userId: UUID,
    ): Response<Unit>
}
