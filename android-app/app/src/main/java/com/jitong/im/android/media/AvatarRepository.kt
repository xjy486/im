package com.jitong.im.android.media

import com.jitong.im.android.local.EncryptedMediaCache
import com.jitong.im.android.group.GroupAvatarUploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal class AvatarRepository(
    private val api: MediaApi,
    private val currentUser: () -> UUID?,
    private val cache: () -> EncryptedMediaCache?,
) : GroupAvatarUploader {
    suspend fun currentProfile(): AvatarProfileResponse = withContext(Dispatchers.IO) {
        currentUser()?.let { api.profile(it).bodyOrThrow("Profile load") }
            ?: throw IOException("No signed-in user")
    }

    suspend fun loadUserAvatar(userId: UUID, avatarVersion: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            if (avatarVersion <= 0) return@withContext null
            val name = userAvatarCacheName(userId, avatarVersion)
            val encryptedCache = cache() ?: return@withContext null
            encryptedCache.get(name)?.let { return@withContext it }
            encryptedCache.deleteMatching("avatar-$userId-v", name)
            val bytes = api.downloadAvatar(userId, "thumb", avatarVersion).readBodyBytesOrNull()
                ?: return@withContext null
            encryptedCache.put(name, bytes)
            bytes
        }

    suspend fun loadGroupAvatar(conversationId: UUID, avatarVersion: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            if (avatarVersion <= 0) return@withContext null
            val name = groupAvatarCacheName(conversationId, avatarVersion)
            val encryptedCache = cache() ?: return@withContext null
            encryptedCache.get(name)?.let { return@withContext it }
            encryptedCache.deleteMatching("group-avatar-$conversationId-v", name)
            val bytes = api.downloadGroupAvatar(conversationId, "thumb", avatarVersion)
                .readBodyBytesOrNull()
                ?: return@withContext null
            encryptedCache.put(name, bytes)
            bytes
        }

    suspend fun loadAvatarUrl(avatarUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        val encryptedCache = cache() ?: return@withContext null
        val name = "group-avatar-search-" + sha256(avatarUrl)
        encryptedCache.get(name)?.let { return@withContext it }
        val bytes = api.downloadAvatarUrl(avatarUrl).readBodyBytesOrNull()
            ?: return@withContext null
        encryptedCache.put(name, bytes)
        bytes
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

    private fun Response<ResponseBody>.readBodyBytesOrNull(): ByteArray? {
        if (!isSuccessful) return null
        return try {
            body()?.bytes()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
    }

    private fun <T> Response<T>.bodyOrThrow(operation: String): T {
        if (isSuccessful && body() != null) return body()!!
        throw IOException("$operation failed with HTTP ${code()}")
    }

    private fun Response<Void>.requireSuccess(operation: String) {
        if (!isSuccessful) throw IOException("$operation failed with HTTP ${code()}")
    }
}
