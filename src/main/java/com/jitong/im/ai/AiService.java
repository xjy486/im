package com.jitong.im.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.auth.AuthenticatedDevice;
import com.jitong.im.auth.UuidV7;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AiService {

    private static final int MAX_SUMMARY_MESSAGES = 100;
    private static final int MAX_QUEUED_SUMMARIES_PER_USER = 3;
    private static final int PROMPT_OVERHEAD_TOKEN_RESERVATION = 2_048;
    private static final Duration SUMMARY_RETENTION = Duration.ofDays(30);
    private static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Shanghai");

    private final AiRepository repository;
    private final SyncService syncService;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AiService(
            AiRepository repository,
            SyncService syncService,
            AiProperties properties,
            ObjectMapper objectMapper
    ) {
        this(repository, syncService, properties, objectMapper, Clock.systemUTC());
    }

    AiService(
            AiRepository repository,
            SyncService syncService,
            AiProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.syncService = syncService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AiConsentResponse updateConsent(
            UUID userId,
            UUID conversationId,
            boolean enabled
    ) {
        AiConversation conversation = repository.findConversationForUpdate(conversationId, userId);
        if (conversation == null) {
            throw new AiException(ApiErrorDefinition.NOT_CONTACT);
        }
        AiConsentResponse response = repository.updateConsent(conversationId, userId, enabled);
        return response;
    }

    @Transactional(readOnly = true)
    public AiConsentResponse consent(UUID userId, UUID conversationId) {
        AiConversation conversation = repository.findConversation(conversationId, userId);
        if (conversation == null) {
            throw new AiException(ApiErrorDefinition.NOT_CONTACT);
        }
        return new AiConsentResponse(
                1,
                conversationId,
                userId,
                conversation.ownerConsent(),
                conversation.enabledForBoth(),
                conversation.policyVersion());
    }

    @Transactional
    public AiJobResponse enqueueSummary(
            AuthenticatedDevice device,
            UUID conversationId,
            AiSummaryRequest request
    ) {
        if (request == null || request.requestId() == null) {
            throw new AiException(ApiErrorDefinition.INVALID_REQUEST);
        }
        AiJobRecord previous = repository.findByRequest(device.userId(), request.requestId());
        if (previous != null) {
            return response(previous);
        }

        AiConversation conversation = repository.findConversationForUpdate(conversationId, device.userId());
        if (conversation == null) {
            throw new AiException(ApiErrorDefinition.NOT_CONTACT);
        }
        previous = repository.findByRequest(device.userId(), request.requestId());
        if (previous != null) {
            return response(previous);
        }
        if (!conversation.enabledForBoth()) {
            throw new AiException(ApiErrorDefinition.AI_CONSENT_REQUIRED);
        }

        long requestedUntil = request.untilSeq() == null ? conversation.lastSeq() : request.untilSeq();
        long requestedAfter = request.afterSeq() == null
                ? Math.max(0, conversation.ownerReadSeq())
                : request.afterSeq();
        if (requestedAfter < 0
                || requestedUntil < requestedAfter
                || requestedUntil > conversation.lastSeq()) {
            throw new AiException(ApiErrorDefinition.INVALID_REQUEST);
        }

        List<AiContextMessage> context = repository.listContext(
                conversationId,
                requestedAfter,
                requestedUntil,
                MAX_SUMMARY_MESSAGES);
        if (context.isEmpty()) {
            throw new AiException(ApiErrorDefinition.INVALID_REQUEST);
        }
        long fromSeq = context.get(0).conversationSeq();
        long toSeq = context.get(context.size() - 1).conversationSeq();
        String contextDigest = AiContextDigest.sha256(context);
        String cacheKey = AiCacheKey.summary(
                device.userId(),
                conversationId,
                fromSeq,
                toSeq,
                "openai-compatible",
                properties.provider().model(),
                properties.promptVersion(),
                false,
                contextDigest);
        String contextJson = writeJson(context);
        Instant now = clock.instant();
        LocalDate budgetDate = now.atZone(BUDGET_ZONE).toLocalDate();
        AiCacheEntry cached = repository.findCache(device.userId(), cacheKey, now);
        if (cached != null) {
            UUID cachedJobId = UuidV7.random();
            repository.insertCachedJob(
                    cachedJobId,
                    device.userId(),
                    device.deviceId(),
                    conversationId,
                    request.requestId(),
                    fromSeq,
                    toSeq,
                    contextDigest,
                    contextJson,
                    conversation.policyVersion(),
                    cacheKey,
                    budgetDate,
                    properties.provider().model(),
                    properties.promptVersion(),
                    cached.resultJson(),
                    now,
                    cached.expiresAt());
            repository.createArtifact(
                    cachedJobId,
                    device.userId(),
                    cached.resultJson(),
                    cached.expiresAt());
            syncService.recordEventForUsers(
                    List.of(device.userId()),
                    "AI_JOB_COMPLETED",
                    cachedJobId,
                    conversationId);
            return response(repository.findJob(device.userId(), cachedJobId));
        }
        long reservedTokens = Math.addExact(
                Math.addExact(
                        PROMPT_OVERHEAD_TOKEN_RESERVATION,
                        contextJson.getBytes(StandardCharsets.UTF_8).length),
                properties.budget().maxOutputTokens());
        if (!repository.reserveBudget(
                device.userId(),
                budgetDate,
                properties.budget().dailyTokenLimit(),
                reservedTokens)) {
            throw new AiException(ApiErrorDefinition.AI_BUDGET_EXCEEDED);
        }
        if (repository.countQueuedJobs(device.userId()) >= MAX_QUEUED_SUMMARIES_PER_USER) {
            throw new AiException(ApiErrorDefinition.AI_BUSY);
        }
        UUID jobId = UuidV7.random();
        Instant expiresAt = now.plus(SUMMARY_RETENTION);
        repository.insertJob(
                jobId,
                device.userId(),
                device.deviceId(),
                conversationId,
                request.requestId(),
                fromSeq,
                toSeq,
                contextDigest,
                contextJson,
                conversation.policyVersion(),
                cacheKey,
                budgetDate,
                reservedTokens,
                properties.provider().model(),
                properties.promptVersion(),
                expiresAt);
        syncService.recordEventForUsers(
                List.of(device.userId()),
                "AI_JOB_QUEUED",
                jobId,
                conversationId);
        return response(repository.findJob(device.userId(), jobId));
    }

    @Transactional(readOnly = true)
    public AiJobResponse job(UUID ownerUserId, UUID jobId) {
        AiJobRecord job = repository.findJob(ownerUserId, jobId);
        if (job == null) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
        return response(job);
    }

    public AiDelivery deliveryForSync(UUID ownerUserId, UUID jobId) {
        AiJobRecord job = repository.findJob(ownerUserId, jobId);
        return job == null
                ? null
                : new AiDelivery(
                        job.jobId(),
                        job.conversationId(),
                        job.kind(),
                        job.status(),
                        job.errorCode(),
                        job.resultJson() == null ? null : readSummary(job.resultJson()));
    }

    @Transactional(readOnly = true)
    public List<AiArtifactResponse> artifacts(UUID ownerUserId) {
        return repository.listArtifacts(ownerUserId, clock.instant()).stream()
                .map(this::artifactResponse)
                .toList();
    }

    @Transactional
    public void deleteArtifact(UUID ownerUserId, UUID artifactId) {
        int deleted = repository.deleteArtifact(ownerUserId, artifactId);
        if (deleted == 0) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
    }

    @Transactional
    public void deleteJob(UUID ownerUserId, UUID jobId) {
        AiJobRecord job = repository.findJobForUpdate(ownerUserId, jobId);
        if (job == null) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
        repository.deleteArtifactsForJob(ownerUserId, jobId);
        repository.releaseBudget(job);
        if (repository.deleteJob(ownerUserId, jobId) == 0) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
    }

    private AiArtifactResponse artifactResponse(AiRepository.AiArtifactRecord record) {
        return new AiArtifactResponse(
                1,
                record.artifactId(),
                record.jobId(),
                record.artifactType(),
                readSummary(record.contentJson()),
                record.createdAt(),
                record.expiresAt());
    }

    AiJobResponse response(AiJobRecord job) {
        return new AiJobResponse(
                1,
                job.jobId(),
                job.ownerUserId(),
                job.requestingDeviceId(),
                job.conversationId(),
                job.requestId(),
                job.kind(),
                job.status(),
                job.fromSeq(),
                job.toSeq(),
                job.model(),
                job.promptVersion(),
                job.errorCode(),
                job.createdAt(),
                job.startedAt(),
                job.finishedAt(),
                job.expiresAt(),
                job.resultJson() == null ? null : readSummary(job.resultJson()));
    }

    private AiSummary readSummary(String json) {
        try {
            return objectMapper.readValue(json, AiSummary.class);
        } catch (JsonProcessingException exception) {
            throw new AiException(ApiErrorDefinition.INTERNAL_ERROR, exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AiException(ApiErrorDefinition.INTERNAL_ERROR, exception);
        }
    }
}
