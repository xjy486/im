package com.jitong.im.android.media

import com.jitong.im.android.local.EncryptedMediaCache
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Response
import java.io.IOException
import java.util.UUID

internal class AvatarRepository(
    private val api: MediaApi,
    private val currentUser: () -> UUID?,
    private val cache: () -> EncryptedMediaCache?,
) {
    suspend fun currentProfile(): AvatarProfileResponse =
        currentUser()?.let { api.profile(it).bodyOrThrow("Profile load") }
            ?: throw IOException("No signed-in user")
    suspend fun loadUserAvatar(userId: UUID, avatarVersion: Long): ByteArray? {
        if (avatarVersion <= 0) return null
        val name = userAvatarCacheName(userId, avatarVersion)
        val encryptedCache = cache() ?: return null
        encryptedCache.get(name)?.let { return it }
        encryptedCache.deleteMatching("avatar-$userId-v", name)
        val response = api.downloadAvatar(userId, "thumb", avatarVersion)
        if (!response.isSuccessful) return null
        val bytes = response.body()?.bytes() ?: return null
        encryptedCache.put(name, bytes)
        return bytes
    }

    suspend fun loadGroupAvatar(conversationId: UUID, avatarVersion: Long): ByteArray? {
        if (avatarVersion <= 0) return null
        val name = groupAvatarCacheName(conversationId, avatarVersion)
        val encryptedCache = cache() ?: return null
        encryptedCache.get(name)?.let { return it }
        encryptedCache.deleteMatching("group-avatar-$conversationId-v", name)
        val response = api.downloadGroupAvatar(conversationId, "thumb", avatarVersion)
        if (!response.isSuccessful) return null
        val bytes = response.body()?.bytes() ?: return null
        encryptedCache.put(name, bytes)
        return bytes
    }

    suspend fun replaceUserAvatar(
        source: ByteArray,
        cropX: Int? = null,
        cropY: Int? = null,
        cropWidth: Int? = null,
        cropHeight: Int? = null,
    ): AvatarUploadResponse {
        val response = api.replaceAvatar(
            uploadId = UUID.randomUUID(),
            file = MultipartBody.Part.createFormData(
                "file",
                "avatar.bin",
                source.toRequestBody("application/octet-stream".toMediaType()),
            ),
            cropX = cropX,
            cropY = cropY,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
        )
        return response.bodyOrThrow("Avatar replacement")
    }

    suspend fun removeUserAvatar() {
        api.removeAvatar().requireSuccess("Avatar removal")
    }

    private fun userAvatarCacheName(userId: UUID, version: Long) =
        "avatar-$userId-v$version"

    private fun groupAvatarCacheName(conversationId: UUID, version: Long) =
        "group-avatar-$conversationId-v$version"

    private fun <T> Response<T>.bodyOrThrow(operation: String): T {
        if (isSuccessful && body() != null) return body()!!
        throw IOException("$operation failed with HTTP ${code()}")
    }

    private fun Response<Void>.requireSuccess(operation: String) {
        if (!isSuccessful) throw IOException("$operation failed with HTTP ${code()}")
    }
}
