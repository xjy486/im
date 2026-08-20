package com.jitong.im.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jitong.im.android.local.LocalMessageEntity

@Composable
internal fun ImageMessageContent(
    message: LocalMessageEntity,
    loadMedia: suspend (LocalMessageEntity, Boolean) -> ByteArray?,
) {
    var preview by remember(message.messageId, message.mediaId, message.localMediaPath) {
        mutableStateOf<ByteArray?>(null)
    }
    var showFullImage by remember(message.messageId) {
        mutableStateOf(false)
    }
    var fullImage by remember(message.messageId) {
        mutableStateOf<ByteArray?>(null)
    }
    var fullImageLoading by remember(message.messageId) {
        mutableStateOf(false)
    }
    val previewBitmap = remember(preview) { preview.decodeBitmap() }
    val fullImageBitmap = remember(fullImage) { fullImage.decodeBitmap() }

    LaunchedEffect(message.messageId, message.mediaId, message.localMediaPath) {
        preview = loadMedia(message, true)
    }
    previewBitmap?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "图片消息",
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = message.mediaId != null) {
                    showFullImage = true
                },
        )
    } ?: Text("图片加载中…")

    if (showFullImage) {
        LaunchedEffect(message.messageId, showFullImage) {
            fullImageLoading = true
            fullImage = loadMedia(message, false)
            fullImageLoading = false
        }
        Dialog(onDismissRequest = { showFullImage = false }) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .heightIn(max = 720.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("完整图片", style = MaterialTheme.typography.titleMedium)
                    when {
                        fullImageLoading -> CircularProgressIndicator()
                        fullImageBitmap != null -> Image(
                            bitmap = fullImageBitmap.asImageBitmap(),
                            contentDescription = "完整图片预览",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        else -> Text("完整图片加载失败，请重试")
                    }
                    TextButton(
                        onClick = { showFullImage = false },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

private fun ByteArray?.decodeBitmap(): Bitmap? =
    this?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
