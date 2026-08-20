package com.jitong.im.media;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
class MediaCleanupJob {

    private final MediaRepository repository;
    private final MediaStorage storage;

    MediaCleanupJob(MediaRepository repository, MediaStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Scheduled(
            fixedDelayString = "${jitong.media.cleanup-interval:3600000}",
            initialDelayString = "${jitong.media.cleanup-initial-delay:60000}")
    void cleanExpiredTemporaryMedia() {
        Instant now = Instant.now();
        Duration lifetime = Duration.ofHours(24);
        for (MediaRecord candidate : repository.findCleanupCandidates(now.minus(lifetime))) {
            if ("TEMP".equals(candidate.state())) {
                repository.markExpired(candidate.mediaId(), now);
            }
            try {
                storage.delete(candidate.originalObjectKey());
                storage.delete(candidate.thumbnailObjectKey());
                repository.markObjectsDeleted(candidate.mediaId(), now);
            } catch (RuntimeException ignored) {
                // Keep EXPIRED rows without objects_deleted_at so the next run retries.
            }
        }
    }
}
