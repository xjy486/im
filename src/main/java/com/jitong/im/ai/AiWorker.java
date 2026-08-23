package com.jitong.im.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
class AiWorker {

    private final AiRepository repository;
    private final AiProvider provider;
    private final AiJobLifecycle lifecycle;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    AiWorker(
            AiRepository repository,
            AiProvider provider,
            AiJobLifecycle lifecycle,
            ObjectMapper objectMapper
    ) {
        this(
                repository,
                provider,
                lifecycle,
                objectMapper,
                Clock.systemUTC());
    }

    AiWorker(
            AiRepository repository,
            AiProvider provider,
            AiJobLifecycle lifecycle,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.provider = provider;
        this.lifecycle = lifecycle;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${jitong.ai.worker.poll-interval:250}")
    void processOne() {
        AiJobRecord job = lifecycle.claim(clock.instant());
        if (job == null) {
            return;
        }

        AiProviderResult providerResult;
        try {
            AiConversation conversation = repository.findConversation(
                    job.conversationId(),
                    job.ownerUserId());
            if (!contextStillAuthorized(job, conversation)) {
                lifecycle.fail(job, ApiErrorDefinition.AI_CONTEXT_CHANGED.code(), clock.instant());
                return;
            }
            List<AiContextMessage> messages = objectMapper.readValue(
                    job.contextJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AiContextMessage.class));
            AiSummaryContext context = new AiSummaryContext(job.conversationId(), messages);
            providerResult = provider.summarize(context);
            if (providerResult.usageReported()
                    && providerResult.totalTokens() > job.reservedTokens()) {
                throw new AiProviderException(
                        "AI_PROVIDER_USAGE_EXCEEDED",
                        "The AI provider reported more tokens than were reserved");
            }
            AiSummary summary = providerResult.summary();
            AiSummaryValidator.validate(summary, context);
            AiConversation current = repository.findConversation(
                    job.conversationId(),
                    job.ownerUserId());
            if (!contextStillAuthorized(job, current)) {
                lifecycle.fail(job, ApiErrorDefinition.AI_CONTEXT_CHANGED.code(), clock.instant());
                return;
            }
            Instant finishedAt = clock.instant();
            lifecycle.complete(
                    job,
                    providerResult,
                    objectMapper.writeValueAsString(summary),
                    finishedAt,
                    finishedAt.plus(Duration.ofDays(30)));
        } catch (AiProviderException exception) {
            if (job.attemptCount() < 2 && AiProviderFailures.isRetryable(exception)) {
                lifecycle.retry(job);
            } else {
                lifecycle.fail(job, exception.code(), clock.instant());
            }
        } catch (Exception exception) {
            lifecycle.fail(job, ApiErrorDefinition.INTERNAL_ERROR.code(), clock.instant());
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
