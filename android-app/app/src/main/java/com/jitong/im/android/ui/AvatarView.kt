package com.jitong.im.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
internal fun AvatarView(
    bytes: ByteArray?,
    fallback: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val bitmap = remember(bytes) {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "头像",
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(fallback.take(2), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
internal fun RemoteAvatar(
    userId: UUID,
    avatarVersion: Long,
    fallback: String,
    load: suspend (UUID, Long) -> ByteArray?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    var bytes by remember(userId, avatarVersion) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(userId, avatarVersion) {
        bytes = load(userId, avatarVersion)
    }
    AvatarView(bytes, fallback, modifier, size)
}

@Composable
internal fun RemoteGroupAvatar(
    conversationId: UUID,
    avatarVersion: Long,
    fallback: String,
    load: suspend (UUID, Long) -> ByteArray?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    var bytes by remember(conversationId, avatarVersion) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(conversationId, avatarVersion) {
        bytes = load(conversationId, avatarVersion)
    }
    AvatarView(bytes, fallback, modifier, size)
}

@Composable
internal fun RemoteSearchGroupAvatar(
    avatarUrl: String,
    fallback: String,
    load: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    var bytes by remember(avatarUrl) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(avatarUrl) {
        bytes = load(avatarUrl)
    }
    AvatarView(bytes, fallback, modifier, size)
}
