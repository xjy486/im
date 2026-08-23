package com.jitong.im.ai;

import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
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
    private final SyncService syncService;
    private final Clock clock;
    private final Duration runningLeaseTimeout;

    @Autowired
    AiRetentionJob(
            AiRepository repository,
            AiJobLifecycle lifecycle,
            SyncService syncService,
            AiProperties properties
    ) {
        this(
                repository,
                lifecycle,
                syncService,
                Clock.systemUTC(),
                properties.worker().leaseTimeout());
    }

    AiRetentionJob(
            AiRepository repository,
            AiJobLifecycle lifecycle,
            SyncService syncService,
            Clock clock,
            Duration runningLeaseTimeout
    ) {
        this.repository = repository;
        this.lifecycle = lifecycle;
        this.syncService = syncService;
        this.clock = clock;
        this.runningLeaseTimeout = runningLeaseTimeout;
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
        for (AiJobRecord job : repository.findStaleRunningJobsForUpdate(
                now.minus(runningLeaseTimeout),
                EXPIRY_BATCH_SIZE)) {
            if (job.attemptCount() < 2) {
                lifecycle.retry(job);
            } else {
                lifecycle.fail(
                        job,
                        ApiErrorDefinition.AI_WORKER_LEASE_EXPIRED.code(),
                        now);
            }
        }
        for (AiRepository.AiArtifactDeletionRecord artifact
                : repository.findExpiredArtifactsForUpdate(now, EXPIRY_BATCH_SIZE)) {
            if (repository.deleteArtifactById(artifact.artifactId()) == 1) {
                syncService.recordEventForUsers(
                        java.util.List.of(artifact.ownerUserId()),
                        "AI_ARTIFACT_DELETED",
                        artifact.artifactId(),
                        artifact.conversationId());
            }
        }
        repository.deleteExpiredCacheEntries(now);
        repository.deleteExpiredTerminalJobs(now, now.minus(EXPIRED_METADATA_RETENTION));
    }
}
