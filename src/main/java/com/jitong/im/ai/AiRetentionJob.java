package com.jitong.im.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
class AiRetentionJob {

    private static final int EXPIRY_BATCH_SIZE = 100;
    private static final Duration EXPIRED_METADATA_RETENTION = Duration.ofDays(30);

    private final AiRepository repository;
    private final AiJobLifecycle lifecycle;
    private final Clock clock;

    @Autowired
    AiRetentionJob(AiRepository repository, AiJobLifecycle lifecycle) {
        this(repository, lifecycle, Clock.systemUTC());
    }

    AiRetentionJob(AiRepository repository, AiJobLifecycle lifecycle, Clock clock) {
        this.repository = repository;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${jitong.ai.retention-interval:60000}",
            initialDelayString = "${jitong.ai.retention-initial-delay:60000}")
    @Transactional
    void expireAndPrunePrivateData() {
        Instant now = clock.instant();
        for (AiJobRecord job : repository.findExpiredActiveJobsForUpdate(now, EXPIRY_BATCH_SIZE)) {
            lifecycle.expire(job, now);
        }
        repository.deleteExpiredArtifacts(now);
        repository.deleteExpiredCacheEntries(now);
        repository.deleteExpiredTerminalJobs(now, now.minus(EXPIRED_METADATA_RETENTION));
    }
}
