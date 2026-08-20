package com.jitong.im.android.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jitong.im.android.local.EncryptedMediaCache
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EncryptedMediaCacheTest {
    private lateinit var directory: File
    private lateinit var cache: EncryptedMediaCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "media-cache-test-${UUID.randomUUID()}")
        cache = EncryptedMediaCache(directory, "jitong.test.media.${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        cache.clear()
        cache.deleteKey()
    }

    @Test
    fun media_bytes_are_encrypted_on_disk_and_can_be_read_back() {
        val original = "private image bytes".toByteArray()

        val relativePath = cache.put("image", original)
        val stored = File(directory, relativePath).readBytes()

        assertFalse(stored.contentEquals(original))
        assertArrayEquals(original, cache.get("image"))
    }

    @Test
    fun clear_removes_the_encrypted_media_directory() {
        cache.put("image", byteArrayOf(1, 2, 3))

        cache.clear()

        assertFalse(directory.exists())
    }
}
