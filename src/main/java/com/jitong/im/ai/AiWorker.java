package com.jitong.im.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.platform.observability.OperationalMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
class AiWorker {

    private static final Logger log = LoggerFactory.getLogger(AiWorker.class);
    private final AiRepository repository;
    private final AiProvider provider;
    private final AiJobLifecycle lifecycle;
    private final ObjectMapper objectMapper;
    private final AiContextImageLoader imageLoader;
    private final AiProperties properties;
    private final OperationalMetrics metrics;
    private final Clock clock;

    @Autowired
    AiWorker(
            AiRepository repository,
            AiProvider provider,
            AiJobLifecycle lifecycle,
            ObjectMapper objectMapper,
            AiContextImageLoader imageLoader,
            AiProperties properties,
            OperationalMetrics metrics
    ) {
        this(
                repository,
                provider,
                lifecycle,
                objectMapper,
                imageLoader,
                properties,
                metrics,
                Clock.systemUTC());
    }

    AiWorker(
            AiRepository repository,
            AiProvider provider,
            AiJobLifecycle lifecycle,
            ObjectMapper objectMapper,
            AiContextImageLoader imageLoader,
            AiProperties properties,
            Clock clock
    ) {
        this(
                repository,
                provider,
                lifecycle,
                objectMapper,
                imageLoader,
                properties,
                new OperationalMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                clock);
    }

    AiWorker(
            AiRepository repository,
            AiProvider provider,
            AiJobLifecycle lifecycle,
            ObjectMapper objectMapper,
            AiContextImageLoader imageLoader,
            AiProperties properties,
            OperationalMetrics metrics,
            Clock clock
    ) {
        this.repository = repository;
        this.provider = provider;
        this.lifecycle = lifecycle;
        this.objectMapper = objectMapper;
        this.imageLoader = imageLoader;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${jitong.ai.worker.poll-interval:250}")
    void processOne() {
        AiJobRecord job;
        try {
            updateQueueMetrics();
            job = lifecycle.claim(clock.instant());
        } catch (RuntimeException exception) {
            log.warn(
                    "ai_worker_claim_failed requestId={} exceptionType={}",
                    requestId(),
                    exception.getClass().getName());
            return;
        }
        if (job == null) {
            return;
        }

        AiProviderResult<?> providerResult;
        try {
            List<AiContextMessage> messages = objectMapper.readValue(
                    job.contextJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AiContextMessage.class));
            AiConversation conversation = repository.findConversation(
                    job.conversationId(),
                    job.ownerUserId());
            if (!contextStillAuthorized(job, conversation, messages)) {
                lifecycle.fail(job, ApiErrorDefinition.CONTEXT_CHANGED.code(), clock.instant());
                return;
            }
            boolean sendImages = job.imageInputEnabled()
                    && properties.imageInput().enabled()
                    && provider.supportsVision()
                    && !"SMART_REPLY".equals(job.kind());
            AiSummaryContext context = new AiSummaryContext(
                    job.conversationId(),
                    messages,
                    imageLoader.load(job.ownerUserId(), messages, sendImages));
            providerResult = callProvider(job.kind(), context);
            if (providerResult.usageReported()
                    && providerResult.totalTokens() > job.reservedTokens()) {
                throw new AiProviderException(
                        "AI_PROVIDER_USAGE_EXCEEDED",
                        "The AI provider reported more tokens than were reserved");
            }
            AiConversation current = repository.findConversation(
                    job.conversationId(),
                    job.ownerUserId());
            if (!contextStillAuthorized(job, current, messages)) {
                lifecycle.fail(job, ApiErrorDefinition.CONTEXT_CHANGED.code(), clock.instant());
                return;
            }
            Instant finishedAt = clock.instant();
            lifecycle.complete(
                    job,
                    providerResult,
                    providerResult.result(),
                    objectMapper.writeValueAsString(providerResult.result()),
                    finishedAt,
                    finishedAt.plus(retention(job.kind())));
        } catch (AiProviderException exception) {
            if (job.attemptCount() < 2 && AiProviderFailures.isRetryable(exception)) {
                lifecycle.retry(job);
            } else {
                lifecycle.fail(job, exception.code(), clock.instant());
            }
        } catch (Exception exception) {
            log.warn(
                    "ai_worker_failed jobId={} errorCode={} requestId={}",
                    job.jobId(),
                    ApiErrorDefinition.INTERNAL_ERROR.code(),
                    requestId());
            lifecycle.fail(job, ApiErrorDefinition.INTERNAL_ERROR.code(), clock.instant());
        } finally {
            updateQueueMetrics();
        }
    }

    private String requestId() {
        return com.jitong.im.platform.observability.RequestContextFilter.requestIdOrNull();
    }

    private void updateQueueMetrics() {
        try {
            AiRepository.AiQueueDepth depth = repository.queueDepth();
            metrics.updateAiQueue(depth.queued(), depth.running());
        } catch (RuntimeException exception) {
            log.warn(
                    "ai_queue_metrics_refresh_failed requestId={} exceptionType={}",
                    requestId(),
                    exception.getClass().getName());
        }
    }

    private AiProviderResult<?> callProvider(String kind, AiSummaryContext context) {
        return switch (kind) {
            case "SUMMARY" -> {
                AiProviderResult<AiSummary> result = provider.summarize(context);
                AiSummaryValidator.validate(result.result(), context);
                yield result;
            }
            case "SMART_REPLY" -> {
                AiProviderResult<AiSmartReplies> result = provider.smartReplies(context);
                AiSmartRepliesValidator.validate(result.result());
                yield result;
            }
            case "EXTRACTION" -> {
                AiProviderResult<AiExtraction> result = provider.extractInformation(context);
                AiExtractionValidator.validate(result.result(), context);
                yield result;
            }
            default -> throw new AiProviderException("AI_INVALID_JOB_KIND", "Unsupported AI job kind");
        };
    }

    private Duration retention(String kind) {
        return "SMART_REPLY".equals(kind) ? Duration.ofMinutes(10) : Duration.ofDays(30);
    }

    private boolean contextStillAuthorized(
            AiJobRecord job,
            AiConversation conversation,
            List<AiContextMessage> originalContext
    ) {
        if (conversation == null) {
            return false;
        }
        if (!conversation.aiEnabled()
                || conversation.policyVersion() != job.aiPolicyVersion()
                || conversation.membershipVersion() != job.membershipVersion()
                || conversation.lastSeq() < job.toSeq()) {
            return false;
        }
        List<AiContextMessage> currentContext = switch (job.kind()) {
            case "EXTRACTION" -> repository.listContextByMessageIds(
                    job.conversationId(),
                    originalContext.stream().map(AiContextMessage::messageId).toList(),
                    conversation.historyVisibleAfterSeq(),
                    200);
            case "SMART_REPLY" -> repository.listTextContext(
                    job.conversationId(),
                    Math.max(job.fromSeq() - 1, conversation.historyVisibleAfterSeq()),
                    job.toSeq(),
                    20);
            default -> repository.listContext(
                    job.conversationId(),
                    Math.max(job.fromSeq() - 1, conversation.historyVisibleAfterSeq()),
                    job.toSeq(),
                    100);
        };
        return AiContextDigest.sha256(currentContext, job.imageInputEnabled())
                .equals(job.contextDigest());
    }
}
