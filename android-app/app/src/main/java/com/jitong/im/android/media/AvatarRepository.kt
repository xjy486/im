package com.jitong.im.android.media

import com.jitong.im.android.local.EncryptedMediaCache
import com.jitong.im.android.group.GroupAvatarUploader
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal class AvatarRepository(
    private val api: MediaApi,
    private val currentUser: () -> UUID?,
    private val cache: () -> EncryptedMediaCache?,
) : GroupAvatarUploader {
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

    suspend fun loadAvatarUrl(avatarUrl: String): ByteArray? {
        val encryptedCache = cache() ?: return null
        val name = "group-avatar-search-" + sha256(avatarUrl)
        encryptedCache.get(name)?.let { return it }
        val response = api.downloadAvatarUrl(avatarUrl)
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
        val result = response.bodyOrThrow("Avatar replacement")
        currentUser()?.let { userId ->
            cache()?.deleteMatching("avatar-$userId-v", "avatar-$userId-v${result.avatarVersion}")
        }
        return result
    }

    suspend fun removeUserAvatar() {
        api.removeAvatar().requireSuccess("Avatar removal")
        currentUser()?.let { userId ->
            cache()?.deleteMatching("avatar-$userId-v")
        }
    }

    override suspend fun replaceGroupAvatar(
        conversationId: UUID,
        source: ByteArray,
    ): AvatarUploadResponse {
        val response = api.replaceGroupAvatar(
            conversationId = conversationId,
            uploadId = UUID.randomUUID(),
            file = MultipartBody.Part.createFormData(
                "file",
                "group-avatar.bin",
                source.toRequestBody("application/octet-stream".toMediaType()),
            ),
        )
        val result = response.bodyOrThrow("Group avatar replacement")
        cache()?.deleteMatching(
            "group-avatar-$conversationId-v",
            "group-avatar-$conversationId-v${result.avatarVersion}",
        )
        return result
    }

    private fun userAvatarCacheName(userId: UUID, version: Long) =
        "avatar-$userId-v$version"

    private fun groupAvatarCacheName(conversationId: UUID, version: Long) =
        "group-avatar-$conversationId-v$version"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun <T> Response<T>.bodyOrThrow(operation: String): T {
        if (isSuccessful && body() != null) return body()!!
        throw IOException("$operation failed with HTTP ${code()}")
    }

    private fun Response<Void>.requireSuccess(operation: String) {
        if (!isSuccessful) throw IOException("$operation failed with HTTP ${code()}")
    }
}
