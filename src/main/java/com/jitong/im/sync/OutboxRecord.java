package com.jitong.im.sync;

import java.time.Instant;
import java.util.UUID;

public record OutboxRecord(
        UUID id,
        String eventType,
        UUID entityId,
        UUID conversationId,
        long syncSeq,
        UUID targetDeviceId,
        int attemptCount,
        Instant nextAttemptAt
) {
}
