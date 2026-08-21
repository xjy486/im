package com.jitong.im.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Component
class MediaCleanupJob {

    private static final List<String> MANAGED_OBJECT_PREFIXES = List.of(
            "message-images/",
            "avatars/user/",
            "avatars/group/");
    private static final Logger log = LoggerFactory.getLogger(MediaCleanupJob.class);
    private final MediaRepository repository;
    private final MediaStorage storage;
    private final Clock clock;
    private final Duration orphanCleanupGrace;

    @Autowired
    MediaCleanupJob(
            MediaRepository repository,
            MediaStorage storage,
            MediaProperties properties
    ) {
        this(repository, storage, Clock.systemUTC(), properties.orphanCleanupGrace());
    }

    MediaCleanupJob(MediaRepository repository, MediaStorage storage, Clock clock) {
        this(repository, storage, clock, Duration.ofHours(24));
    }

    MediaCleanupJob(
            MediaRepository repository,
            MediaStorage storage,
            Clock clock,
            Duration orphanCleanupGrace
    ) {
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
        this.orphanCleanupGrace = orphanCleanupGrace;
    }

    @Scheduled(
            fixedDelayString = "${jitong.media.cleanup-interval:3600000}",
            initialDelayString = "${jitong.media.cleanup-initial-delay:60000}")
    void cleanMedia() {
        cleanExpiredTemporaryMedia();
        cleanOrphanedObjects();
    }

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
                        "media_cleanup_failed mediaId={}",
                        candidate.mediaId(),
                        exception);
            }
        }
    }

    void cleanOrphanedObjects() {
        Instant cutoff = clock.instant().minus(orphanCleanupGrace);
        Set<String> referencedObjectKeys;
        try {
            referencedObjectKeys = repository.findReferencedObjectKeys();
        } catch (RuntimeException exception) {
            log.warn("media_orphan_reference_scan_failed", exception);
            return;
        }
        for (String prefix : MANAGED_OBJECT_PREFIXES) {
            Iterable<MediaStorage.StoredObject> objects;
            try {
                objects = storage.list(prefix);
            } catch (RuntimeException exception) {
                log.warn("media_orphan_scan_failed prefix={}", prefix, exception);
                continue;
            }
            try {
                for (MediaStorage.StoredObject object : objects) {
                    if (!object.lastModified().isBefore(cutoff)
                            || referencedObjectKeys.contains(object.objectKey())) {
                        continue;
                    }
                    try {
                        storage.delete(object.objectKey());
                        log.info("media_orphan_deleted prefix={} lastModified={}",
                                prefix, object.lastModified());
                    } catch (RuntimeException exception) {
                        log.warn(
                                "media_orphan_delete_failed prefix={} lastModified={}",
                                prefix,
                                object.lastModified(),
                                exception);
                    }
                }
            } catch (RuntimeException exception) {
                log.warn("media_orphan_scan_failed prefix={}", prefix, exception);
            }
        }
    }
}
