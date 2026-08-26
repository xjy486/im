package com.jitong.im.android.message

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.jitong.im.android.local.EncryptedMediaCache
import com.jitong.im.android.media.MediaApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import retrofit2.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MessageRepositoryMediaTest {
    private lateinit var cacheDirectory: File
    private lateinit var cache: EncryptedMediaCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        cacheDirectory = File(context.cacheDir, "repository-media-test-${UUID.randomUUID()}")
        cache = EncryptedMediaCache(cacheDirectory, "jitong.repository.media.${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        cache.clear()
        cache.deleteKey()
    }

    @Test
    fun repository_loads_thumbnail_and_full_image_then_uses_encrypted_cache() = runTest {
        val mediaId = UUID.randomUUID()
        val thumbnailBytes = "thumbnail".toByteArray()
        val fullBytes = "full-image".toByteArray()
        val requestedVariants = mutableListOf<String>()
        val mediaApi = object : MediaApi {
            override suspend fun uploadImage(
                uploadId: UUID,
                file: okhttp3.MultipartBody.Part,
            ): Response<com.jitong.im.android.media.MediaUploadResponse> =
                error("not used")

            override suspend fun download(
                mediaId: UUID,
                variant: String,
            ): Response<okhttp3.ResponseBody> {
                requestedVariants += variant
                val bytes = if (variant == "thumb") thumbnailBytes else fullBytes
                return Response.success(bytes.toResponseBody("image/jpeg".toMediaType()))
            }

            override suspend fun replaceAvatar(
                uploadId: UUID,
                file: okhttp3.MultipartBody.Part,
                cropX: Int?,
                cropY: Int?,
                cropWidth: Int?,
                cropHeight: Int?,
            ): Response<com.jitong.im.android.media.AvatarUploadResponse> =
                error("not used")

            override suspend fun profile(
                userId: UUID,
            ): Response<com.jitong.im.android.media.AvatarProfileResponse> =
                error("not used")

            override suspend fun removeAvatar(): Response<Void> = error("not used")

            override suspend fun updateProfile(
                request: com.jitong.im.android.media.UserProfileUpdateRequest,
            ): Response<com.jitong.im.android.media.AvatarProfileResponse> =
                error("not used")

            override suspend fun downloadAvatar(
                userId: UUID,
                variant: String,
                avatarVersion: Long?,
            ): Response<okhttp3.ResponseBody> = error("not used")

            override suspend fun replaceGroupAvatar(
                conversationId: UUID,
                uploadId: UUID,
                file: okhttp3.MultipartBody.Part,
            ): Response<com.jitong.im.android.media.AvatarUploadResponse> =
                error("not used")

            override suspend fun removeGroupAvatar(
                conversationId: UUID,
            ): Response<Void> = error("not used")

            override suspend fun downloadGroupAvatar(
                conversationId: UUID,
                variant: String,
                avatarVersion: Long?,
            ): Response<okhttp3.ResponseBody> = error("not used")

            override suspend fun downloadAvatarUrl(
                avatarUrl: String,
            ): Response<okhttp3.ResponseBody> = error("not used")
        }
        val repository = MessageRepository(
            api = unusedMessageApi(),
            syncApi = unusedSyncApi(),
            database = { null },
            webSocket = MessageWebSocket(
                OkHttpClient(),
                "http://127.0.0.1/",
                { null },
                Gson(),
            ),
            mediaApi = mediaApi,
            mediaCache = { cache },
        )
        val message = com.jitong.im.android.local.LocalMessageEntity(
            messageId = "message",
            conversationId = "conversation",
            senderId = "sender",
            clientMsgId = UUID.randomUUID().toString(),
            conversationSeq = 1,
            type = "IMAGE",
            state = "ACTIVE",
            localState = "RECEIVED",
            text = "",
            mediaId = mediaId.toString(),
            localMediaPath = null,
            serverAcceptedAt = "2026-08-21T00:00:00Z",
            createdAt = 1,
        )

        assertArrayEquals(thumbnailBytes, repository.loadMedia(message, true))
        assertArrayEquals(fullBytes, repository.loadMedia(message, false))
        assertArrayEquals(thumbnailBytes, repository.loadMedia(message, true))
        assertArrayEquals(fullBytes, repository.loadMedia(message, false))
        assertEquals(listOf("thumb", "full"), requestedVariants)
    }

    @Test
    fun recalled_image_deletes_full_thumb_and_pending_local_media() = runTest {
        val mediaId = UUID.randomUUID()
        cache.put(mediaId.toString(), "full".toByteArray())
        cache.put("$mediaId-thumb", "thumb".toByteArray())
        val localMediaPath = cache.put("pending-local-message", "pending".toByteArray())
        val requestedVariants = mutableListOf<String>()
        val mediaApi = object : MediaApi {
            override suspend fun uploadImage(
                uploadId: UUID,
                file: okhttp3.MultipartBody.Part,
            ): Response<com.jitong.im.android.media.MediaUploadResponse> = error("not used")

            override suspend fun download(
                mediaId: UUID,
                variant: String,
            ): Response<okhttp3.ResponseBody> {
                requestedVariants += variant
                return Response.success("unexpected".toResponseBody("image/jpeg".toMediaType()))
            }

            override suspend fun replaceAvatar(
                uploadId: UUID,
                file: okhttp3.MultipartBody.Part,
                cropX: Int?,
                cropY: Int?,
                cropWidth: Int?,
                cropHeight: Int?,
            ): Response<com.jitong.im.android.media.AvatarUploadResponse> =
                error("not used")

            override suspend fun profile(
                userId: UUID,
            ): Response<com.jitong.im.android.media.AvatarProfileResponse> =
                error("not used")

            override suspend fun removeAvatar(): Response<Void> = error("not used")

            override suspend fun updateProfile(
                request: com.jitong.im.android.media.UserProfileUpdateRequest,
            ): Response<com.jitong.im.android.media.AvatarProfileResponse> =
                error("not used")

            override suspend fun downloadAvatar(
                userId: UUID,
                variant: String,
                avatarVersion: Long?,
            ): Response<okhttp3.ResponseBody> = error("not used")

            override suspend fun replaceGroupAvatar(
                conversationId: UUID,
                uploadId: UUID,
                file: okhttp3.MultipartBody.Part,
            ): Response<com.jitong.im.android.media.AvatarUploadResponse> =
                error("not used")

            override suspend fun removeGroupAvatar(
                conversationId: UUID,
            ): Response<Void> = error("not used")

            override suspend fun downloadGroupAvatar(
                conversationId: UUID,
                variant: String,
                avatarVersion: Long?,
            ): Response<okhttp3.ResponseBody> = error("not used")

            override suspend fun downloadAvatarUrl(
                avatarUrl: String,
            ): Response<okhttp3.ResponseBody> = error("not used")
        }
        val repository = MessageRepository(
            api = unusedMessageApi(),
            syncApi = unusedSyncApi(),
            database = { null },
            webSocket = MessageWebSocket(
                OkHttpClient(),
                "http://127.0.0.1/",
                { null },
                Gson(),
            ),
            mediaApi = mediaApi,
            mediaCache = { cache },
        )
        val message = com.jitong.im.android.local.LocalMessageEntity(
            messageId = "message",
            conversationId = "conversation",
            senderId = "sender",
            clientMsgId = UUID.randomUUID().toString(),
            conversationSeq = 1,
            type = "IMAGE",
            state = "RECALLED",
            localState = "RECEIVED",
            text = "",
            mediaId = mediaId.toString(),
            localMediaPath = localMediaPath,
            serverAcceptedAt = "2026-08-21T00:00:00Z",
            recalledAt = "2026-08-21T00:00:30Z",
            createdAt = 1,
        )

        assertEquals(null, repository.loadMedia(message, true))
        assertEquals(null, repository.loadMedia(message, false))
        assertEquals(emptyList<String>(), requestedVariants)
        assertEquals(null, cache.get(mediaId.toString()))
        assertEquals(null, cache.get("$mediaId-thumb"))
        assertEquals(null, cache.getByPath(localMediaPath))
    }

    private fun unusedMessageApi() = object : MessageApi {
        override suspend fun send(
            conversationId: UUID,
            request: SendMessageRequest,
        ): Response<MessageResponse> = error("not used")

        override suspend fun history(
            conversationId: UUID,
            afterSeq: Long,
            limit: Int,
        ): Response<MessagePageResponse> = error("not used")

        override suspend fun recall(
            messageId: UUID,
        ): Response<MessageResponse> = error("not used")
    }

    private fun unusedSyncApi() = object : SyncApi {
        override suspend fun page(
            afterSeq: Long,
            untilSeq: Long?,
            limit: Int,
        ): Response<SyncPageResponse> = error("not used")

        override suspend fun acknowledge(request: SyncAckRequest): Response<SyncAckResponse> =
            error("not used")

        override suspend fun conversations(): Response<List<SyncConversationResponse>> =
            error("not used")

        override suspend fun groups(): Response<List<com.jitong.im.android.group.GroupSummary>> =
            error("not used")

        override suspend fun groupProfile(
            conversationId: UUID,
        ): Response<GroupProfileResponse> = error("not used")

        override suspend fun profile(
            userId: UUID,
        ): Response<UserProfileResponse> = error("not used")

        override suspend fun readStates(
            conversationId: UUID,
        ): Response<ReadStatePageResponse> = error("not used")

        override suspend fun markRead(
            conversationId: UUID,
            request: ReadStateRequest,
        ): Response<ReadStatePageResponse> = error("not used")

        override suspend fun aiArtifacts(): Response<List<AiArtifactResponse>> = error("not used")

        override suspend fun aiActionItems(): Response<List<AiActionItemResponse>> = error("not used")
    }
}
