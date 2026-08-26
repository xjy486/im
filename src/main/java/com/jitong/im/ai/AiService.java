package com.jitong.im.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.auth.AuthenticatedDevice;
import com.jitong.im.auth.UuidV7;
import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.AuditSubjectType;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
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
    private static final int MAX_SMART_REPLY_MESSAGES = 20;
    private static final int MAX_EXTRACTION_MESSAGES = 200;
    private static final int MAX_QUEUED_AI_JOBS_PER_USER = 3;
    private static final int PROMPT_OVERHEAD_TOKEN_RESERVATION = 2_048;
    private static final int TOKENS_RESERVED_PER_IMAGE = 2_048;
    private static final Duration SUMMARY_RETENTION = Duration.ofDays(30);
    private static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Shanghai");

    private final AiRepository repository;
    private final SyncService syncService;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final AiProvider provider;
    private final SecurityAuditSink auditSink;
    private final Clock clock;

    @Autowired
    public AiService(
            AiRepository repository,
            SyncService syncService,
            AiProperties properties,
            ObjectMapper objectMapper,
            AiProvider provider,
            SecurityAuditSink auditSink
    ) {
        this(
                repository,
                syncService,
                properties,
                objectMapper,
                provider,
                auditSink,
                Clock.systemUTC());
    }

    AiService(
            AiRepository repository,
            SyncService syncService,
            AiProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this(
                repository,
                syncService,
                properties,
                objectMapper,
                new UnavailableAiProvider(),
                event -> {
                },
                clock);
    }

    AiService(
            AiRepository repository,
            SyncService syncService,
            AiProperties properties,
            ObjectMapper objectMapper,
            AiProvider provider,
            Clock clock
    ) {
        this(
                repository,
                syncService,
                properties,
                objectMapper,
                provider,
                event -> {
                },
                clock);
    }

    AiService(
            AiRepository repository,
            SyncService syncService,
            AiProperties properties,
            ObjectMapper objectMapper,
            AiProvider provider,
            SecurityAuditSink auditSink,
            Clock clock
    ) {
        this.repository = repository;
        this.syncService = syncService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.provider = provider;
        this.auditSink = auditSink;
        this.clock = clock;
    }

    @Transactional
    public AiConsentResponse updateConsent(
            UUID userId,
            UUID conversationId,
            boolean enabled,
            UUID requestId
    ) {
        AiConversation conversation = repository.findConversationForUpdate(conversationId, userId);
        if (conversation == null || !"C2C".equals(conversation.type())) {
            throw new AiException(ApiErrorDefinition.NOT_CONTACT);
        }
        AiConsentResponse response = repository.updateConsent(conversationId, userId, enabled);
        syncService.recordEventForUsers(
                List.of(userId, conversation.peerUserId()),
                "CONVERSATION_AI_POLICY_CHANGED",
                conversationId,
                conversationId);
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.C2C_AI_POLICY_CHANGE,
                AuditOutcome.SUCCEEDED,
                userId,
                null,
                AuditSubjectType.CONVERSATION,
                conversationId,
                requestId,
                null,
                clock.instant()));
        return response;
    }

    public AiConsentResponse updateConsent(
            UUID userId,
            UUID conversationId,
            boolean enabled
    ) {
        return updateConsent(userId, conversationId, enabled, null);
    }

    @Transactional(readOnly = true)
    public AiConsentResponse consent(UUID userId, UUID conversationId) {
        AiConversation conversation = repository.findConversation(conversationId, userId);
        if (conversation == null || !"C2C".equals(conversation.type())) {
            throw new AiException(ApiErrorDefinition.NOT_CONTACT);
        }
        return new AiConsentResponse(
                1,
                conversationId,
                userId,
                conversation.ownerConsent(),
                conversation.aiEnabled(),
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
        // Account retirement updates the same user row and keeps that lock until
        // all private AI data has been erased. Locking the active owner first
        // makes enqueue either commit before that erasure or reject afterwards.
        if (!repository.lockActiveOwnerForUpdate(device.userId())) {
            throw new AiException(ApiErrorDefinition.AUTH_INVALID);
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
        if (!conversation.aiEnabled()) {
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
        requestedAfter = Math.max(requestedAfter, conversation.historyVisibleAfterSeq());
        if (requestedUntil < requestedAfter) {
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
        boolean imageInputEnabled = imageInputEnabled();
        String contextDigest = AiContextDigest.sha256(context, imageInputEnabled);
        String cacheKey = AiCacheKey.summary(
                device.userId(),
                conversationId,
                fromSeq,
                toSeq,
                "openai-compatible",
                properties.provider().model(),
                properties.promptVersion(),
                imageInputEnabled,
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
                    "SUMMARY",
                    fromSeq,
                    toSeq,
                    contextDigest,
                    conversation.policyVersion(),
                    conversation.membershipVersion(),
                    cacheKey,
                    budgetDate,
                    properties.provider().model(),
                    properties.promptVersion(),
                    imageInputEnabled,
                    cached.resultJson(),
                    now,
                    cached.expiresAt());
            repository.createArtifact(
                    cachedJobId,
                    device.userId(),
                    "SUMMARY",
                    cached.resultJson(),
                    cached.expiresAt());
            syncService.recordEventForUsers(
                    List.of(device.userId()),
                    "AI_JOB_COMPLETED",
                    cachedJobId,
                    conversationId);
            return response(repository.findJob(device.userId(), cachedJobId));
        }
        long reservedTokens = reservedTokens(contextJson, context, imageInputEnabled);
        if (!repository.reserveBudget(
                device.userId(),
                budgetDate,
                properties.budget().dailyTokenLimit(),
                reservedTokens)) {
            throw new AiException(ApiErrorDefinition.AI_BUDGET_EXCEEDED);
        }
        if (repository.countQueuedJobs(device.userId()) >= MAX_QUEUED_AI_JOBS_PER_USER) {
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
                "SUMMARY",
                fromSeq,
                toSeq,
                contextDigest,
                contextJson,
                conversation.policyVersion(),
                conversation.membershipVersion(),
                cacheKey,
                budgetDate,
                reservedTokens,
                properties.provider().model(),
                properties.promptVersion(),
                imageInputEnabled,
                expiresAt);
        syncService.recordEventForUsers(
                List.of(device.userId()),
                "AI_JOB_QUEUED",
                jobId,
                conversationId);
        return response(repository.findJob(device.userId(), jobId));
    }

    @Transactional
    public AiJobResponse enqueueSmartReplies(
            AuthenticatedDevice device,
            UUID conversationId,
            AiSmartReplyRequest request
    ) {
        if (request == null || request.requestId() == null) {
            throw new AiException(ApiErrorDefinition.INVALID_REQUEST);
        }
        if (!repository.lockActiveOwnerForUpdate(device.userId())) {
            throw new AiException(ApiErrorDefinition.AUTH_INVALID);
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
        if (!conversation.aiEnabled()) {
            throw new AiException(ApiErrorDefinition.AI_CONSENT_REQUIRED);
        }

        List<AiContextMessage> context = repository.listTextContext(
                conversationId,
                conversation.historyVisibleAfterSeq(),
                conversation.lastSeq(),
                MAX_SMART_REPLY_MESSAGES);
        if (context.isEmpty()) {
            throw new AiException(ApiErrorDefinition.INVALID_REQUEST);
        }
        long fromSeq = context.get(0).conversationSeq();
        long toSeq = context.get(context.size() - 1).conversationSeq();
        String contextDigest = AiContextDigest.sha256(context, false);
        String cacheKey = AiCacheKey.forContext(
                device.userId(),
                "SMART_REPLY",
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
        long reservedTokens = reservedTokens(contextJson, context, false);
        if (repository.countActiveJobs(device.userId()) > 0) {
            throw new AiException(ApiErrorDefinition.AI_BUSY);
        }
        if (!repository.reserveBudget(
                device.userId(),
                budgetDate,
                properties.budget().dailyTokenLimit(),
                reservedTokens)) {
            throw new AiException(ApiErrorDefinition.AI_BUDGET_EXCEEDED);
        }
        UUID jobId = UuidV7.random();
        Instant expiresAt = now.plus(Duration.ofMinutes(10));
        repository.insertJob(
                jobId,
                device.userId(),
                device.deviceId(),
                conversationId,
                request.requestId(),
                "SMART_REPLY",
                fromSeq,
                toSeq,
                contextDigest,
                contextJson,
                conversation.policyVersion(),
                conversation.membershipVersion(),
                cacheKey,
                budgetDate,
                reservedTokens,
                properties.provider().model(),
                properties.promptVersion(),
                false,
                expiresAt);
        syncService.recordEventForUsers(
                List.of(device.userId()),
                "AI_JOB_QUEUED",
                jobId,
                conversationId);
        return response(repository.findJob(device.userId(), jobId));
    }

    @Transactional
    public AiJobResponse enqueueExtraction(
            AuthenticatedDevice device,
            UUID conversationId,
            AiExtractionRequest request
    ) {
        if (request == null
                || request.requestId() == null
                || request.messageIds() == null
                || request.messageIds().isEmpty()
                || request.messageIds().size() > MAX_EXTRACTION_MESSAGES
                || request.messageIds().stream().anyMatch(java.util.Objects::isNull)
                || request.messageIds().stream().distinct().count() != request.messageIds().size()) {
            throw new AiException(ApiErrorDefinition.INVALID_REQUEST);
        }
        if (!repository.lockActiveOwnerForUpdate(device.userId())) {
            throw new AiException(ApiErrorDefinition.AUTH_INVALID);
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
        if (!conversation.aiEnabled()) {
            throw new AiException(ApiErrorDefinition.AI_CONSENT_REQUIRED);
        }

        List<AiContextMessage> context = repository.listContextByMessageIds(
                conversationId,
                request.messageIds(),
                conversation.historyVisibleAfterSeq(),
                MAX_EXTRACTION_MESSAGES);
        if (context.size() != request.messageIds().size()) {
            throw new AiException(ApiErrorDefinition.INVALID_REQUEST);
        }
        long fromSeq = context.get(0).conversationSeq();
        long toSeq = context.get(context.size() - 1).conversationSeq();
        boolean imageInputEnabled = imageInputEnabled();
        String contextDigest = AiContextDigest.sha256(context, imageInputEnabled);
        String cacheKey = AiCacheKey.forContext(
                device.userId(),
                "EXTRACTION",
                conversationId,
                fromSeq,
                toSeq,
                "openai-compatible",
                properties.provider().model(),
                properties.promptVersion(),
                imageInputEnabled,
                contextDigest);
        String contextJson = writeJson(context);
        Instant now = clock.instant();
        LocalDate budgetDate = now.atZone(BUDGET_ZONE).toLocalDate();
        long reservedTokens = reservedTokens(contextJson, context, imageInputEnabled);
        if (repository.countQueuedJobs(device.userId()) >= MAX_QUEUED_AI_JOBS_PER_USER) {
            throw new AiException(ApiErrorDefinition.AI_BUSY);
        }
        if (!repository.reserveBudget(
                device.userId(),
                budgetDate,
                properties.budget().dailyTokenLimit(),
                reservedTokens)) {
            throw new AiException(ApiErrorDefinition.AI_BUDGET_EXCEEDED);
        }
        UUID jobId = UuidV7.random();
        Instant expiresAt = now.plus(SUMMARY_RETENTION);
        repository.insertJob(
                jobId,
                device.userId(),
                device.deviceId(),
                conversationId,
                request.requestId(),
                "EXTRACTION",
                fromSeq,
                toSeq,
                contextDigest,
                contextJson,
                conversation.policyVersion(),
                conversation.membershipVersion(),
                cacheKey,
                budgetDate,
                reservedTokens,
                properties.provider().model(),
                properties.promptVersion(),
                imageInputEnabled,
                expiresAt);
        syncService.recordEventForUsers(
                List.of(device.userId()),
                "AI_JOB_QUEUED",
                jobId,
                conversationId);
        return response(repository.findJob(device.userId(), jobId));
    }

    @Transactional
    public GroupPolicyUpdateResult updateGroupPolicy(
            UUID actorUserId,
            UUID conversationId,
            boolean enabled
    ) {
        return applyGroupPolicy(actorUserId, conversationId, enabled, false);
    }

    @Transactional
    public long resetGroupPolicyForOwnershipTransfer(
            UUID actorUserId,
            UUID conversationId
    ) {
        return applyGroupPolicy(actorUserId, conversationId, false, true).policyVersion();
    }

    private GroupPolicyUpdateResult applyGroupPolicy(
            UUID actorUserId,
            UUID conversationId,
            boolean enabled,
            boolean force
    ) {
        AiConversation conversation = repository.findConversationForUpdate(
                conversationId,
                actorUserId);
        if (conversation == null || !"GROUP".equals(conversation.type())) {
            throw new AiException(ApiErrorDefinition.NOT_MEMBER);
        }
        if (!force && conversation.aiEnabled() == enabled) {
            return new GroupPolicyUpdateResult(conversation.policyVersion(), false);
        }
        long policyVersion = repository.updateGroupPolicy(conversationId, enabled);
        terminateJobs(
                repository.findActiveJobsForConversationForUpdate(conversationId),
                "CANCELLED");
        return new GroupPolicyUpdateResult(policyVersion, true);
    }

    @Transactional
    public void invalidateGroupMemberJobs(UUID conversationId, UUID ownerUserId) {
        repository.lockConversationForUpdate(conversationId);
        terminateJobs(
                repository.findActiveJobsForOwnerInConversationForUpdate(
                        conversationId,
                        ownerUserId),
                "FAILED");
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
                        job.resultJson() == null ? null : readResult(job.kind(), job.resultJson()));
    }

    @Transactional(readOnly = true)
    public List<AiArtifactResponse> artifacts(UUID ownerUserId) {
        return repository.listArtifacts(ownerUserId, clock.instant()).stream()
                .map(this::artifactResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AiActionItemResponse> actionItems(UUID ownerUserId) {
        return repository.listActionItems(ownerUserId).stream()
                .map(this::actionItemResponse)
                .toList();
    }

    @Transactional
    public AiActionItemResponse updateActionItem(
            UUID ownerUserId,
            UUID actionItemId,
            AiActionItemUpdate request
    ) {
        if (request == null
                || !("OPEN".equals(request.status()) || "COMPLETED".equals(request.status()))) {
            throw new AiException(ApiErrorDefinition.INVALID_REQUEST);
        }
        AiRepository.AiActionItemRecord item = repository.findActionItem(ownerUserId, actionItemId);
        if (item == null) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
        Instant completedAt = "COMPLETED".equals(request.status()) ? clock.instant() : null;
        if (repository.updateActionItemStatus(
                ownerUserId,
                actionItemId,
                request.status(),
                completedAt) == 0) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
        syncService.recordEventForUsers(
                List.of(ownerUserId),
                "AI_ACTION_ITEM_UPDATED",
                actionItemId,
                item.conversationId());
        return actionItemResponse(repository.findActionItem(ownerUserId, actionItemId));
    }

    @Transactional
    public void deleteActionItem(UUID ownerUserId, UUID actionItemId) {
        AiRepository.AiActionItemRecord item = repository.findActionItem(ownerUserId, actionItemId);
        if (item == null || repository.deleteActionItem(ownerUserId, actionItemId) == 0) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
        syncService.recordEventForUsers(
                List.of(ownerUserId),
                "AI_ACTION_ITEM_DELETED",
                actionItemId,
                item.conversationId());
    }

    @Transactional
    public void deleteArtifact(UUID ownerUserId, UUID artifactId) {
        AiRepository.AiArtifactDeletionRecord target = repository.findArtifactDeletionContext(
                ownerUserId,
                artifactId);
        if (target == null) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
        // Enqueue holds the same row lock from cache lookup through cached-result
        // insertion. Taking it here prevents a request that already observed the
        // cache from recreating private content after deletion returns.
        repository.findConversationForUpdate(target.conversationId(), ownerUserId);
        List<AiJobRecord> jobs = repository.findJobsForContentDeletionForUpdate(
                ownerUserId,
                target.jobId(),
                target.cacheKey());
        List<AiRepository.AiArtifactDeletionRecord> artifacts =
                repository.findArtifactsForContentDeletionForUpdate(
                        ownerUserId,
                        target.jobId(),
                        target.cacheKey());
        if (artifacts.stream().noneMatch(artifact -> artifact.artifactId().equals(artifactId))) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
        jobs.forEach(repository::releaseBudget);
        repository.eraseResultCopies(
                ownerUserId,
                target.jobId(),
                target.cacheKey(),
                clock.instant());
        artifacts.forEach(artifact -> syncService.recordEventForUsers(
                List.of(ownerUserId),
                "AI_ARTIFACT_DELETED",
                artifact.artifactId(),
                artifact.conversationId()));
    }

    @Transactional
    public void deleteJob(UUID ownerUserId, UUID jobId) {
        AiJobRecord candidate = repository.findJob(ownerUserId, jobId);
        if (candidate == null) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
        repository.findConversationForUpdate(candidate.conversationId(), ownerUserId);
        AiJobRecord job = repository.findJobForUpdate(ownerUserId, jobId);
        if (job == null) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
        repository.deleteArtifactsForJob(ownerUserId, jobId);
        repository.deleteActionItemsForJob(ownerUserId, jobId);
        repository.deleteCacheEntry(ownerUserId, job.cacheKey());
        repository.releaseBudget(job);
        if (repository.deleteJob(ownerUserId, jobId) == 0) {
            throw new AiException(ApiErrorDefinition.AI_NOT_FOUND);
        }
        syncService.recordEventForUsers(
                List.of(ownerUserId),
                "AI_JOB_DELETED",
                jobId,
                job.conversationId());
    }

    private void terminateJobs(List<AiJobRecord> jobs, String status) {
        Instant finishedAt = clock.instant();
        for (AiJobRecord job : jobs) {
            repository.releaseBudget(job);
            if (repository.terminateActiveJob(
                    job,
                    status,
                    ApiErrorDefinition.CONTEXT_CHANGED.code(),
                    finishedAt) == 0) {
                throw new IllegalStateException("Active AI job could not be terminated");
            }
            syncService.recordEventForUsers(
                    List.of(job.ownerUserId()),
                    "AI_JOB_FAILED",
                    job.jobId(),
                    job.conversationId());
        }
    }

    public record GroupPolicyUpdateResult(long policyVersion, boolean changed) {
    }

    private AiArtifactResponse artifactResponse(AiRepository.AiArtifactRecord record) {
        return new AiArtifactResponse(
                1,
                record.artifactId(),
                record.jobId(),
                record.conversationId(),
                record.artifactType(),
                readResult(record.artifactType(), record.contentJson()),
                record.createdAt(),
                record.expiresAt());
    }

    private AiActionItemResponse actionItemResponse(AiRepository.AiActionItemRecord record) {
        return new AiActionItemResponse(
                1,
                record.actionItemId(),
                record.sourceJobId(),
                record.ownerUserId(),
                record.conversationId(),
                record.assigneeUserId(),
                record.title(),
                record.details(),
                record.dueAt(),
                record.priority(),
                record.confidence(),
                record.sourceMessageIds(),
                record.status(),
                record.createdAt(),
                record.completedAt());
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
                job.resultJson() == null ? null : readResult(job.kind(), job.resultJson()));
    }

    private Object readResult(String kind, String json) {
        try {
            return switch (kind) {
                case "SUMMARY" -> objectMapper.readValue(json, AiSummary.class);
                case "SMART_REPLY" -> objectMapper.readValue(json, AiSmartReplies.class);
                case "EXTRACTION" -> objectMapper.readValue(json, AiExtraction.class);
                default -> throw new AiException(ApiErrorDefinition.INTERNAL_ERROR);
            };
        } catch (JsonProcessingException exception) {
            throw new AiException(ApiErrorDefinition.INTERNAL_ERROR, exception);
        }
    }

    private boolean imageInputEnabled() {
        return properties.imageInput().enabled() && provider.supportsVision();
    }

    private long reservedTokens(
            String contextJson,
            List<AiContextMessage> context,
            boolean imageInputEnabled
    ) {
        long imageCount = imageInputEnabled
                ? context.stream()
                        .filter(AiContextMessage::hasAuthorizedImageReference)
                        .limit(AiContextImageLoader.MAX_IMAGES_PER_TASK)
                        .count()
                : 0;
        return Math.addExact(
                Math.addExact(
                        Math.addExact(
                                PROMPT_OVERHEAD_TOKEN_RESERVATION,
                                contextJson.getBytes(StandardCharsets.UTF_8).length),
                        properties.budget().maxOutputTokens()),
                Math.multiplyExact(imageCount, TOKENS_RESERVED_PER_IMAGE));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AiException(ApiErrorDefinition.INTERNAL_ERROR, exception);
        }
    }
}
