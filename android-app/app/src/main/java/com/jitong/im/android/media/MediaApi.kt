package com.jitong.im.android.media

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
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
