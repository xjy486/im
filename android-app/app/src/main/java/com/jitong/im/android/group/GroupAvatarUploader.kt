package com.jitong.im.android.group

import com.jitong.im.android.media.AvatarUploadResponse
import java.util.UUID

internal interface GroupAvatarUploader {
    suspend fun replaceGroupAvatar(
        conversationId: UUID,
        source: ByteArray,
    ): AvatarUploadResponse
}
