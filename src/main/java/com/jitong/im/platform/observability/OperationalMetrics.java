package com.jitong.im.platform.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OperationalMetrics {

    private final AtomicInteger activeWebSocketConnections = new AtomicInteger();
    private final AtomicLong outboxBacklog = new AtomicLong();
    private final AtomicInteger queuedAiJobs = new AtomicInteger();
    private final Counter fcmFailures;
    private final Counter syncResets;
    private final Counter aiTokens;
    private final Timer messageAckLatency;

    OperationalMetrics(MeterRegistry registry) {
        Gauge.builder("jitong.wss.connections.active", activeWebSocketConnections, AtomicInteger::get)
                .description("Current active WebSocket connections")
                .register(registry);
        Gauge.builder("jitong.outbox.backlog", outboxBacklog, AtomicLong::get)
                .description("Outbox rows awaiting delivery")
                .register(registry);
        Gauge.builder("jitong.ai.jobs.queued", queuedAiJobs, AtomicInteger::get)
                .description("AI jobs waiting to run")
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

    public Counter fcmFailures() {
        return fcmFailures;
    }

    public Counter syncResets() {
        return syncResets;
    }

    public Counter aiTokens() {
        return aiTokens;
    }

    public Timer messageAckLatency() {
        return messageAckLatency;
    }
}
