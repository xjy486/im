package com.jitong.im.android.media

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.DELETE
import retrofit2.http.Body
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.UUID

internal interface MediaApi {
    @Multipart
    @POST("api/v1/media/images")
    suspend fun uploadImage(
        @Query("uploadId") uploadId: UUID,
        @Part file: MultipartBody.Part,
    ): Response<MediaUploadResponse>

    @Streaming
    @GET("api/v1/media/{mediaId}")
    suspend fun download(
        @Path("mediaId") mediaId: UUID,
        @Query("variant") variant: String = "full",
    ): Response<ResponseBody>

    @Multipart
    @PUT("api/v1/users/me/avatar")
    suspend fun replaceAvatar(
        @Query("uploadId") uploadId: UUID,
        @Part file: MultipartBody.Part,
        @Query("cropX") cropX: Int? = null,
        @Query("cropY") cropY: Int? = null,
        @Query("cropWidth") cropWidth: Int? = null,
        @Query("cropHeight") cropHeight: Int? = null,
    ): Response<AvatarUploadResponse>

    @DELETE("api/v1/users/me/avatar")
    suspend fun removeAvatar(): Response<Void>

    @PUT("api/v1/users/me/profile")
    suspend fun updateProfile(
        @Body request: UserProfileUpdateRequest,
    ): Response<AvatarProfileResponse>

    @Streaming
    @GET("api/v1/users/{userId}/avatar")
    suspend fun downloadAvatar(
        @Path("userId") userId: UUID,
        @Query("variant") variant: String = "thumb",
        @Query("avatarVersion") avatarVersion: Long? = null,
    ): Response<ResponseBody>

    @Multipart
    @PUT("api/v1/groups/{conversationId}/avatar")
    suspend fun replaceGroupAvatar(
        @Path("conversationId") conversationId: UUID,
        @Query("uploadId") uploadId: UUID,
        @Part file: MultipartBody.Part,
    ): Response<AvatarUploadResponse>

    @retrofit2.http.DELETE("api/v1/groups/{conversationId}/avatar")
    suspend fun removeGroupAvatar(@Path("conversationId") conversationId: UUID): Response<Void>

    @Streaming
    @GET("api/v1/groups/{conversationId}/avatar")
    suspend fun downloadGroupAvatar(
        @Path("conversationId") conversationId: UUID,
        @Query("variant") variant: String = "thumb",
        @Query("avatarVersion") avatarVersion: Long? = null,
    ): Response<ResponseBody>

    @Streaming
    @GET
    suspend fun downloadAvatarUrl(@Url avatarUrl: String): Response<ResponseBody>

    @GET("api/v1/users/{userId}/profile")
    suspend fun profile(@Path("userId") userId: UUID): Response<AvatarProfileResponse>
}

internal data class MediaUploadResponse(
    val version: Int,
    val mediaId: UUID,
    val purpose: String,
    val state: String,
    val contentType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
)

internal data class AvatarUploadResponse(
    val version: Int,
    val mediaId: UUID,
    val purpose: String,
    val state: String,
    val contentType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val avatarVersion: Long,
    val thumbnailUrl: String,
)

internal data class AvatarProfileResponse(
    val userId: UUID,
    val displayName: String,
    val avatarUrl: String?,
    val avatarVersion: Long,
    val avatarFallback: String,
)

internal data class UserProfileUpdateRequest(
    val displayName: String,
)
