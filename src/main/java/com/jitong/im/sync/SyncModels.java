package com.jitong.im.sync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record SyncEventRecord(
        long syncSeq,
        String eventType,
        UUID entityId,
        UUID conversationId,
        Instant createdAt
) {
}

record SyncPage(
        int version,
        long afterSeq,
        long highWatermark,
        long untilSeq,
        long nextAfterSeq,
        boolean hasMore,
        List<SyncEventRecord> events
) {
}

record SyncAckResponse(
        int version,
        UUID deviceId,
        long ackedSeq
) {
}

record SyncAckRequest(
        long syncSeq
) {
}
