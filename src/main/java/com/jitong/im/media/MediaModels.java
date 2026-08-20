package com.jitong.im.media;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

record MediaRecord(
        UUID mediaId,
        String purpose,
        UUID uploaderId,
        UUID uploadId,
        String state,
        String originalObjectKey,
        String thumbnailObjectKey,
        String contentType,
        int width,
        int height,
        long byteSize,
        String sha256,
        UUID attachedMessageId,
        Instant createdAt,
        Instant boundAt,
        Instant expiredAt,
        Instant objectsDeletedAt
) {
}

record MediaUploadResponse(
        int version,
        UUID mediaId,
        String purpose,
        String state,
        String contentType,
        int width,
        int height,
        long byteSize,
        String sha256
) {
}

record MediaDownload(
        InputStream content,
        long contentLength,
        String contentType
) {
}
