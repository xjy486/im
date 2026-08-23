package com.jitong.im.ai;

import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
class AiJobLifecycle {

    private final AiRepository repository;
    private final SyncService syncService;

    AiJobLifecycle(AiRepository repository, SyncService syncService) {
        this.repository = repository;
        this.syncService = syncService;
    }

    @Transactional
    AiJobRecord claim(Instant startedAt) {
        AiJobRecord job = repository.claimNextQueued(startedAt);
        if (job != null) {
            syncService.recordEventForUsers(
                    List.of(job.ownerUserId()),
                    "AI_JOB_STARTED",
                    job.jobId(),
                    job.conversationId());
        }
        return job;
    }

    @Transactional
    void complete(
            AiJobRecord job,
            AiProviderResult providerResult,
            String resultJson,
            Instant finishedAt,
            Instant expiresAt
    ) {
        int updated = repository.succeed(
                job.jobId(),
                job.ownerUserId(),
                resultJson,
                providerResult.inputTokens(),
                providerResult.outputTokens(),
                finishedAt,
                expiresAt);
        if (updated == 0) {
            return;
        }
        repository.settleSucceededBudget(job, providerResult.totalTokens());
        repository.createCacheEntry(job, resultJson, expiresAt);
        repository.createArtifact(job.jobId(), job.ownerUserId(), resultJson, expiresAt);
        syncService.recordEventForUsers(
                List.of(job.ownerUserId()),
                "AI_JOB_COMPLETED",
                job.jobId(),
                job.conversationId());
    }

    @Transactional
    void retry(AiJobRecord job) {
        repository.requeue(job.jobId(), job.ownerUserId());
    }

    @Transactional
    void fail(AiJobRecord job, String errorCode, Instant finishedAt) {
        if (repository.fail(job.jobId(), job.ownerUserId(), errorCode, finishedAt) == 0) {
            return;
        }
        repository.releaseBudget(job);
        syncService.recordEventForUsers(
                List.of(job.ownerUserId()),
                "AI_JOB_FAILED",
                job.jobId(),
                job.conversationId());
    }

    @Transactional
    void expire(AiJobRecord job, Instant finishedAt) {
        if (repository.expire(
                job.jobId(),
                job.ownerUserId(),
                ApiErrorDefinition.AI_EXPIRED.code(),
                finishedAt) == 0) {
            return;
        }
        repository.releaseBudget(job);
        syncService.recordEventForUsers(
                List.of(job.ownerUserId()),
                "AI_JOB_FAILED",
                job.jobId(),
                job.conversationId());
    }
}
