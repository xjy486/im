package com.jitong.im.android.group

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

internal interface GroupApi {
    @POST("api/v1/groups")
    suspend fun create(@Body request: CreateGroupRequest): Response<GroupCreateResponse>

    @GET("api/v1/groups")
    suspend fun list(): Response<List<GroupSummary>>

    @GET("api/v1/groups/search")
    suspend fun search(@Query("query") query: String): Response<GroupSearchPage>
}
