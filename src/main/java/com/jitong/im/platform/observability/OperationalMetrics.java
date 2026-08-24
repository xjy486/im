package com.jitong.im.platform.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class OperationalMetrics {

    private final AtomicInteger activeWebSocketConnections = new AtomicInteger();
    private final AtomicLong outboxBacklog = new AtomicLong();
    private final AtomicInteger queuedAiJobs = new AtomicInteger();
    private final AtomicInteger aiRunningJobs = new AtomicInteger();
    private final AtomicLong aiRetryCount = new AtomicLong();
    private final AtomicLong aiFailureCount = new AtomicLong();
    private final AtomicReference<AiQueueSnapshot> aiQueueSnapshot =
            new AtomicReference<>(new AiQueueSnapshot(0, 0));
    private final Counter fcmFailures;
    private final Counter syncResets;
    private final Counter aiTokens;
    private final Counter aiUsageRecords;
    private final Timer messageAckLatency;

    public OperationalMetrics(MeterRegistry registry) {
        Gauge.builder("jitong.wss.connections.active", activeWebSocketConnections, AtomicInteger::get)
                .description("Current active WebSocket connections")
                .register(registry);
        Gauge.builder("jitong.outbox.backlog", outboxBacklog, AtomicLong::get)
                .description("Outbox rows awaiting delivery")
                .register(registry);
        Gauge.builder("jitong.ai.jobs.queued", queuedAiJobs, AtomicInteger::get)
                .description("AI jobs waiting to run")
                .register(registry);
        Gauge.builder("jitong.ai.jobs.running", aiRunningJobs, AtomicInteger::get)
                .description("AI jobs currently running")
                .register(registry);
        Gauge.builder(
                        "jitong.ai.queue.depth",
                        aiQueueSnapshot,
                        snapshot -> snapshot.get().queued() + snapshot.get().running())
                .description("AI jobs queued or running")
                .register(registry);
        Gauge.builder("jitong.ai.queue.queued", aiQueueSnapshot, snapshot -> snapshot.get().queued())
                .description("AI jobs queued")
                .register(registry);
        Gauge.builder("jitong.ai.queue.running", aiQueueSnapshot, snapshot -> snapshot.get().running())
                .description("AI jobs running")
                .register(registry);
        fcmFailures = Counter.builder("jitong.fcm.failures")
                .description("FCM delivery failures")
                .register(registry);
        syncResets = Counter.builder("jitong.sync.resets")
                .description("Devices instructed to perform a full sync reset")
                .register(registry);
        aiTokens = Counter.builder("jitong.ai.tokens")
                .description("AI tokens consumed")
                .register(registry);
        aiUsageRecords = Counter.builder("jitong.ai.usage.records")
                .description("AI jobs with recorded provider usage")
                .register(registry);
        messageAckLatency = Timer.builder("jitong.message.ack.latency")
                .description("Durable message acknowledgement latency")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(2))
                .register(registry);
    }

    public AtomicInteger activeWebSocketConnections() {
        return activeWebSocketConnections;
    }

    public AtomicLong outboxBacklog() {
        return outboxBacklog;
    }

    public AtomicInteger queuedAiJobs() {
        return queuedAiJobs;
    }

    public AtomicInteger aiRunningJobs() {
        return aiRunningJobs;
    }

    public AtomicLong aiRetryCount() {
        return aiRetryCount;
    }

    public AtomicLong aiFailureCount() {
        return aiFailureCount;
    }

    public void updateAiQueue(int queued, int running) {
        queuedAiJobs.set(Math.max(queued, 0));
        aiRunningJobs.set(Math.max(running, 0));
        aiQueueSnapshot.set(new AiQueueSnapshot(
                Math.max(queued, 0),
                Math.max(running, 0)));
    }

    private record AiQueueSnapshot(int queued, int running) {
    }

    public Counter fcmFailures() {
        return fcmFailures;
    }

    public Counter syncResets() {
        return syncResets;
    }

    public Counter aiTokens() {
        return aiTokens;
    }

    public Counter aiUsageRecords() {
        return aiUsageRecords;
    }

    public void recordAiUsage(long tokens, boolean usageReported) {
        if (tokens > 0) {
            aiTokens.increment(tokens);
        }
        if (usageReported) {
            aiUsageRecords.increment();
        }
    }

    public Timer messageAckLatency() {
        return messageAckLatency;
    }
}
