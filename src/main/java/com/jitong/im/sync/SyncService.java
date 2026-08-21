package com.jitong.im.sync;

import com.jitong.im.auth.AuthenticatedDevice;
import com.jitong.im.auth.UuidV7;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SyncService {

    private final SyncRepository repository;

    SyncService(SyncRepository repository) {
        this.repository = repository;
    }

    @Transactional
    SyncPage page(UUID userId, long afterSeq, Long untilSeq, int limit) {
        if (afterSeq < 0 || limit < 1 || limit > 200 || (untilSeq != null && untilSeq < afterSeq)) {
            throw new SyncException(ApiErrorDefinition.INVALID_REQUEST);
        }
        repository.ensureCounter(userId);
        long highWatermark = repository.currentHighWatermark(userId);
        long requestedUntil = untilSeq == null ? highWatermark : untilSeq;
        if (requestedUntil > highWatermark) {
            throw new SyncException(ApiErrorDefinition.INVALID_REQUEST);
        }
        if (afterSeq != requestedUntil) {
            long oldest = repository.retainedWindowStart(userId);
            if (afterSeq < oldest - 1) {
                throw new SyncException(ApiErrorDefinition.SYNC_RESET_REQUIRED);
            }
        }
        List<SyncEventRecord> events = repository.listEvents(userId, afterSeq, requestedUntil, limit);
        if (!events.isEmpty() && events.get(0).syncSeq() != afterSeq + 1) {
            throw new SyncException(ApiErrorDefinition.SYNC_RESET_REQUIRED);
        }
        for (int index = 1; index < events.size(); index++) {
            if (events.get(index).syncSeq() != events.get(index - 1).syncSeq() + 1) {
                throw new SyncException(ApiErrorDefinition.SYNC_RESET_REQUIRED);
            }
        }
        long nextAfter = events.isEmpty() ? afterSeq : events.get(events.size() - 1).syncSeq();
        boolean hasMore = nextAfter < requestedUntil;
        if (hasMore && events.isEmpty()) {
            throw new SyncException(ApiErrorDefinition.SYNC_RESET_REQUIRED);
        }
        return new SyncPage(
                1,
                afterSeq,
                highWatermark,
                requestedUntil,
                nextAfter,
                hasMore,
                List.copyOf(events));
    }

    public long highWatermark(UUID userId) {
        repository.ensureCounter(userId);
        return repository.currentHighWatermark(userId);
    }

    @Transactional
    SyncAckResponse acknowledge(AuthenticatedDevice device, long syncSeq) {
        long highWatermark = repository.currentHighWatermark(device.userId());
        if (syncSeq < 0 || syncSeq > highWatermark) {
            throw new SyncException(ApiErrorDefinition.INVALID_REQUEST);
        }
        if (!repository.isActiveDevice(device.userId(), device.deviceId())) {
            throw new SyncException(ApiErrorDefinition.AUTH_INVALID);
        }
        repository.acknowledge(device.userId(), device.deviceId(), syncSeq);
        return new SyncAckResponse(1, device.deviceId(), syncSeq);
    }

    public long allocateSequence(UUID userId) {
        return repository.nextUserSequence(userId);
    }

    public void recordEvent(UUID userId, long syncSeq, String eventType, UUID entityId, UUID conversationId) {
        repository.insertEvent(userId, syncSeq, eventType, entityId, conversationId);
        for (UUID deviceId : repository.activeDeviceIds(userId)) {
            repository.insertOutbox(
                    UuidV7.random(),
                    eventType,
                    entityId,
                    conversationId,
                    syncSeq,
                    deviceId);
        }
    }

    public void recordEventForUsers(
            List<UUID> userIds,
            String eventType,
            UUID entityId,
            UUID conversationId
    ) {
        userIds.stream().sorted().forEach(userId -> {
            long syncSeq = allocateSequence(userId);
            recordEvent(userId, syncSeq, eventType, entityId, conversationId);
        });
    }
}
