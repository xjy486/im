package com.jitong.im.sync;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

@Component
class SyncRetentionJob {

    private static final Duration RETENTION = Duration.ofDays(30);

    private final SyncRepository repository;
    private final Clock clock;

    @Autowired
    SyncRetentionJob(SyncRepository repository) {
        this(repository, Clock.systemUTC());
    }

    SyncRetentionJob(SyncRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${jitong.sync.retention-interval:86400000}",
            initialDelayString = "${jitong.sync.retention-initial-delay:60000}")
    @Transactional
    void pruneExpiredEvents() {
        repository.pruneExpiredEvents(clock.instant().minus(RETENTION));
    }
}
