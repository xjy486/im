package com.jitong.im.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
class AiWorker {

    private final AiRepository repository;
    private final AiProvider provider;
    private final SyncService syncService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    AiWorker(
            AiRepository repository,
            AiProvider provider,
            SyncService syncService,
            ObjectMapper objectMapper
    ) {
        this(
                repository,
                provider,
                syncService,
                objectMapper,
                Clock.systemUTC());
    }

    AiWorker(
            AiRepository repository,
            AiProvider provider,
            SyncService syncService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.provider = provider;
        this.syncService = syncService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${jitong.ai.worker.poll-interval:250}")
    void processOne() {
        AiJobRecord job = claim();
        if (job == null) {
            return;
        }

        AiSummary summary;
        try {
            AiConversation conversation = repository.findConversation(
                    job.conversationId(),
                    job.ownerUserId());
            if (!contextStillAuthorized(job, conversation)) {
                fail(job, ApiErrorDefinition.AI_CONTEXT_CHANGED.code());
                return;
            }
            List<AiContextMessage> messages = objectMapper.readValue(
                    job.contextJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AiContextMessage.class));
            AiSummaryContext context = new AiSummaryContext(job.conversationId(), messages);
            summary = provider.summarize(context);
            AiSummaryValidator.validate(summary, context);
            AiConversation current = repository.findConversation(
                    job.conversationId(),
                    job.ownerUserId());
            if (!contextStillAuthorized(job, current)) {
                fail(job, ApiErrorDefinition.AI_CONTEXT_CHANGED.code());
                return;
            }
            Instant finishedAt = clock.instant();
            int updated = repository.succeed(
                    job.jobId(),
                    job.ownerUserId(),
                    objectMapper.writeValueAsString(summary),
                    finishedAt,
                    finishedAt.plus(Duration.ofDays(30)));
            if (updated > 0) {
                repository.createArtifact(
                        job.jobId(),
                        job.ownerUserId(),
                        objectMapper.writeValueAsString(summary),
                        finishedAt.plus(Duration.ofDays(30)));
                syncService.recordEventForUsers(
                        List.of(job.ownerUserId()),
                        "AI_JOB_COMPLETED",
                        job.jobId(),
                        job.conversationId());
            }
        } catch (AiProviderException exception) {
            fail(job, exception.code());
        } catch (Exception exception) {
            fail(job, ApiErrorDefinition.INTERNAL_ERROR.code());
        }
    }

    @Transactional
    AiJobRecord claim() {
        AiJobRecord job = repository.claimNextQueued(clock.instant());
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
    void fail(AiJobRecord job, String errorCode) {
        if (repository.fail(job.jobId(), job.ownerUserId(), errorCode, clock.instant()) > 0) {
            syncService.recordEventForUsers(
                    List.of(job.ownerUserId()),
                    "AI_JOB_FAILED",
                    job.jobId(),
                    job.conversationId());
        }
    }

    private boolean contextStillAuthorized(AiJobRecord job, AiConversation conversation) {
        if (conversation == null) {
            return false;
        }
        List<AiContextMessage> currentContext = repository.listContext(
                job.conversationId(),
                job.fromSeq() - 1,
                job.toSeq(),
                100);
        return conversation.enabledForBoth()
                && conversation.policyVersion() == job.aiPolicyVersion()
                && conversation.lastSeq() >= job.toSeq()
                && AiContextDigest.sha256(currentContext).equals(job.contextDigest());
    }
}
