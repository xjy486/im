package com.jitong.im.android.media

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jitong.im.android.local.EncryptedMediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import retrofit2.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AvatarRepositoryTest {
    private lateinit var cacheDirectory: File
    private lateinit var cache: EncryptedMediaCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        cacheDirectory = File(context.cacheDir, "avatar-repository-test-${UUID.randomUUID()}")
        cache = EncryptedMediaCache(cacheDirectory, "jitong.avatar.repository.${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        cache.clear()
        cache.deleteKey()
    }

    @Test
    fun group_avatar_body_is_read_off_the_main_thread() = runBlocking(Dispatchers.Main.immediate) {
        val conversationId = UUID.randomUUID()
        val imageBytes = "group-avatar".toByteArray()
        val api = StubMediaApi(
            groupAvatarResponse = Response.success(
                MainThreadDetectingResponseBody(imageBytes),
            ),
        )
        val repository = AvatarRepository(
            api = api,
            currentUser = { UUID.randomUUID() },
            cache = { cache },
        )

        assertArrayEquals(
            imageBytes,
            repository.loadGroupAvatar(conversationId, avatarVersion = 1),
        )
    }

    private class MainThreadDetectingResponseBody(
        private val bytes: ByteArray,
    ) : ResponseBody() {
        private val mediaType = "image/webp".toMediaType()

        override fun contentType() = mediaType

        override fun contentLength() = bytes.size.toLong()

        override fun source(): BufferedSource {
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "Avatar response body was read on the main thread"
            }
            return bytes.toResponseBody(mediaType).source()
        }
    }

    private class StubMediaApi(
        private val groupAvatarResponse: Response<ResponseBody>,
    ) : MediaApi {
        override suspend fun uploadImage(
            uploadId: UUID,
            file: MultipartBody.Part,
        ): Response<MediaUploadResponse> = error("not used")

        override suspend fun download(
            mediaId: UUID,
            variant: String,
        ): Response<ResponseBody> = error("not used")

        override suspend fun replaceAvatar(
            uploadId: UUID,
            file: MultipartBody.Part,
            cropX: Int?,
            cropY: Int?,
            cropWidth: Int?,
            cropHeight: Int?,
        ): Response<AvatarUploadResponse> = error("not used")

        override suspend fun removeAvatar(): Response<Void> = error("not used")

        override suspend fun downloadAvatar(
            userId: UUID,
            variant: String,
            avatarVersion: Long?,
        ): Response<ResponseBody> = error("not used")

        override suspend fun replaceGroupAvatar(
            conversationId: UUID,
            uploadId: UUID,
            file: MultipartBody.Part,
        ): Response<AvatarUploadResponse> = error("not used")

        override suspend fun removeGroupAvatar(conversationId: UUID): Response<Void> =
            error("not used")

        override suspend fun downloadGroupAvatar(
            conversationId: UUID,
            variant: String,
            avatarVersion: Long?,
        ): Response<ResponseBody> = groupAvatarResponse

        override suspend fun downloadAvatarUrl(avatarUrl: String): Response<ResponseBody> =
            error("not used")

        override suspend fun profile(userId: UUID): Response<AvatarProfileResponse> =
            error("not used")
    }
}
