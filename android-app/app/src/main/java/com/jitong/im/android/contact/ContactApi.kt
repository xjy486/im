package com.jitong.im.android.contact

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.UUID

internal interface ContactApi {
    @GET("api/v1/users/search")
    suspend fun search(@Query("accountNo") accountNo: String): Response<ContactSearchResult>

    @POST("api/v1/contact-requests")
    suspend fun createRequest(@Body request: CreateContactRequest): Response<ContactRequestResponse>

    @GET("api/v1/contact-requests")
    suspend fun requests(): Response<List<ContactRequestSummary>>

    @POST("api/v1/contact-requests/{requestId}/accept")
    suspend fun accept(@Path("requestId") requestId: UUID): Response<ContactRequestResponse>

    @POST("api/v1/contact-requests/{requestId}/reject")
    suspend fun reject(@Path("requestId") requestId: UUID): Response<ContactRequestResponse>

    @POST("api/v1/contact-requests/{requestId}/cancel")
    suspend fun cancel(@Path("requestId") requestId: UUID): Response<ContactRequestResponse>

    @GET("api/v1/contacts")
    suspend fun contacts(): Response<List<ContactSummary>>

    @DELETE("api/v1/contacts/{userId}")
    suspend fun remove(@Path("userId") userId: UUID): Response<Void>

    @POST("api/v1/blocks/{userId}")
    suspend fun block(@Path("userId") userId: UUID): Response<Void>

    @DELETE("api/v1/blocks/{userId}")
    suspend fun unblock(@Path("userId") userId: UUID): Response<Void>

    @GET("api/v1/conversations")
    suspend fun conversations(): Response<List<ConversationSummary>>
}
