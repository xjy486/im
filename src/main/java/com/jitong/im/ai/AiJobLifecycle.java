package com.jitong.im.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.platform.observability.OperationalMetrics;
import com.jitong.im.sync.SyncService;
import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.AuditSubjectType;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import com.jitong.im.auth.UuidV7;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
class AiJobLifecycle {

    private final AiRepository repository;
    private final SyncService syncService;
    private final ObjectMapper objectMapper;
    private final OperationalMetrics metrics;
    private final SecurityAuditSink auditSink;

    @Autowired
    AiJobLifecycle(
            AiRepository repository,
            SyncService syncService,
            ObjectMapper objectMapper,
            OperationalMetrics metrics,
            SecurityAuditSink auditSink
    ) {
        this.repository = repository;
        this.syncService = syncService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.auditSink = auditSink;
    }

    AiJobLifecycle(
            AiRepository repository,
            SyncService syncService,
            ObjectMapper objectMapper
    ) {
        this(
                repository,
                syncService,
                objectMapper,
                new OperationalMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                event -> {
                });
    }

    @Transactional
    AiJobRecord claim(Instant startedAt) {
        AiJobRecord job = repository.claimNextQueued(startedAt);
        if (job != null) {
            updateQueueMetrics();
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
            AiProviderResult<?> providerResult,
            Object result,
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
                job.attemptCount(),
                finishedAt,
                expiresAt);
        if (updated == 0) {
            return;
        }
        metrics.recordAiUsage(providerResult.totalTokens(), providerResult.usageReported());
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.AI_MODEL_USAGE,
                AuditOutcome.SUCCEEDED,
                job.ownerUserId(),
                job.requestingDeviceId(),
                AuditSubjectType.AI_JOB,
                job.jobId(),
                job.requestId(),
                null,
                finishedAt));
        long tokensToSettle = providerResult.usageReported()
                ? providerResult.totalTokens()
                : job.reservedTokens();
        repository.settleSucceededBudget(job, tokensToSettle);
        repository.createCacheEntry(job, resultJson, expiresAt);
        repository.createArtifact(
                job.jobId(),
                job.ownerUserId(),
                job.kind(),
                artifactJson(result, resultJson),
                expiresAt);
        if (result instanceof AiExtraction extraction) {
            repository.createActionItems(job, extraction);
        }
        syncService.recordEventForUsers(
                List.of(job.ownerUserId()),
                "AI_JOB_COMPLETED",
                job.jobId(),
                job.conversationId());
    }

    private String artifactJson(Object result, String resultJson) {
        if (!(result instanceof AiExtraction extraction)) {
            return resultJson;
        }
        try {
            return objectMapper.writeValueAsString(new AiExtraction(List.of(), extraction.keyFacts()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI artifact could not be serialized", exception);
        }
    }

    @Transactional
    void retry(AiJobRecord job) {
        if (repository.requeue(job.jobId(), job.ownerUserId(), job.attemptCount()) == 1) {
            metrics.aiRetryCount().incrementAndGet();
            updateQueueMetrics();
        }
    }

    @Transactional
    void fail(AiJobRecord job, String errorCode, Instant finishedAt) {
        if (repository.fail(
                job.jobId(),
                job.ownerUserId(),
                job.attemptCount(),
                errorCode,
                finishedAt) == 0) {
            return;
        }
        metrics.aiFailureCount().incrementAndGet();
        recordFailureAudit(job, errorCode, finishedAt);
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
                job.attemptCount(),
                ApiErrorDefinition.AI_EXPIRED.code(),
                finishedAt) == 0) {
            return;
        }
        metrics.aiFailureCount().incrementAndGet();
        recordFailureAudit(job, ApiErrorDefinition.AI_EXPIRED.code(), finishedAt);
        repository.releaseBudget(job);
        syncService.recordEventForUsers(
                List.of(job.ownerUserId()),
                "AI_JOB_FAILED",
                job.jobId(),
                job.conversationId());
    }

    private void updateQueueMetrics() {
        AiRepository.AiQueueDepth depth = repository.queueDepth();
        metrics.updateAiQueue(depth.queued(), depth.running());
    }

    private void recordFailureAudit(AiJobRecord job, String errorCode, Instant occurredAt) {
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.AI_MODEL_FAILURE,
                AuditOutcome.FAILED,
                job.ownerUserId(),
                job.requestingDeviceId(),
                AuditSubjectType.AI_JOB,
                job.jobId(),
                job.requestId(),
                null,
                errorCode,
                occurredAt));
    }
}
