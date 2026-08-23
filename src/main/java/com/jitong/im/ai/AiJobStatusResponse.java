package com.jitong.im.ai;

import java.time.Instant;
import java.util.UUID;

record AiJobStatusResponse(
        int version,
        UUID jobId,
        UUID conversationId,
        String kind,
        String status,
        String errorCode,
        Instant createdAt,
        Instant expiresAt
) {
}
