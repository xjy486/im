package com.jitong.im.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jitong.im.android.local.LocalMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

@RunWith(AndroidJUnit4::class)
class ImageMessageContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tapping_a_thumbnail_requests_and_displays_the_complete_image() {
        val message = LocalMessageEntity(
            messageId = "message-1",
            conversationId = "conversation-1",
            senderId = "sender-1",
            clientMsgId = UUID.randomUUID().toString(),
            conversationSeq = 1,
            type = "IMAGE",
            state = "ACTIVE",
            localState = "RECEIVED",
            text = "",
            mediaId = UUID.randomUUID().toString(),
            localMediaPath = null,
            serverAcceptedAt = "2026-08-21T00:00:00Z",
            createdAt = 1,
        )
        val variants = mutableListOf<Boolean>()
        val thumbnail = imageBytes(24, 12)
        val full = imageBytes(240, 120)

        composeRule.setContent {
            MaterialTheme {
                ImageMessageContent(message) { _, isThumbnail ->
                    variants += isThumbnail
                    if (isThumbnail) thumbnail else full
                }
            }
        }

        composeRule.onNodeWithContentDescription("图片消息").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            variants.contains(false)
        }

        composeRule.onNodeWithText("完整图片").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("完整图片预览").assertIsDisplayed()
        assertEquals(listOf(true, false), variants)
    }

    private fun imageBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.MAGENTA)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
