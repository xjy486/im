package com.jitong.im.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
class MediaCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(MediaCleanupJob.class);
    private final MediaRepository repository;
    private final MediaStorage storage;
    private final Clock clock;

    @Autowired
    MediaCleanupJob(MediaRepository repository, MediaStorage storage) {
        this(repository, storage, Clock.systemUTC());
    }

    MediaCleanupJob(MediaRepository repository, MediaStorage storage, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${jitong.media.cleanup-interval:3600000}",
            initialDelayString = "${jitong.media.cleanup-initial-delay:60000}")
    void cleanExpiredTemporaryMedia() {
        Instant now = clock.instant();
        Duration lifetime = Duration.ofHours(24);
        for (MediaRecord candidate : repository.findCleanupCandidates(now.minus(lifetime))) {
            if ("TEMP".equals(candidate.state())) {
                repository.markExpired(candidate.mediaId(), now);
            }
            try {
                storage.delete(candidate.originalObjectKey());
                storage.delete(candidate.thumbnailObjectKey());
                repository.markObjectsDeleted(candidate.mediaId(), now);
            } catch (RuntimeException exception) {
                // Keep EXPIRED rows without objects_deleted_at so the next run retries.
                log.warn(
                        "media_cleanup_failed mediaId={} originalObjectKey={} thumbnailObjectKey={}",
                        candidate.mediaId(),
                        candidate.originalObjectKey(),
                        candidate.thumbnailObjectKey(),
                        exception);
            }
        }
    }
}
