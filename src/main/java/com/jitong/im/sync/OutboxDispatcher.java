package com.jitong.im.sync;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.jitong.im.platform.observability.OperationalMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int BATCH_SIZE = 64;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final OutboxRepository repository;
    private final OutboxDelivery delivery;
    private final OperationalMetrics metrics;
    private final Clock clock;

    @Autowired
    OutboxDispatcher(
            OutboxRepository repository,
            OutboxDelivery delivery,
            OperationalMetrics metrics
    ) {
        this(repository, delivery, metrics, Clock.systemUTC());
    }

    OutboxDispatcher(
            OutboxRepository repository,
            OutboxDelivery delivery,
            OperationalMetrics metrics,
            Clock clock
    ) {
        this.repository = repository;
        this.delivery = delivery;
        this.metrics = metrics;
        this.clock = clock;
    }

    OutboxDispatcher(OutboxRepository repository, OutboxDelivery delivery, Clock clock) {
        this(
                repository,
                delivery,
                new OperationalMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                clock);
    }

    @Scheduled(fixedDelayString = "${jitong.outbox.poll-interval:250}")
    void dispatchDue() {
        try {
            metrics.outboxBacklog().set(repository.pendingCount());
            Instant now = clock.instant();
            List<OutboxRecord> records = repository.claimDue(BATCH_SIZE, now);
            for (OutboxRecord record : records) {
                if (delivery.deliver(record)) {
                    repository.complete(record.id(), now);
                } else {
                    repository.retry(record.id(), now.plus(RETRY_DELAY));
                }
            }
            metrics.outboxBacklog().set(repository.pendingCount());
        } catch (RuntimeException exception) {
            log.warn(
                    "outbox_dispatch_failed requestId={} exceptionType={}",
                    requestId(),
                    exception.getClass().getName());
        }
    }

    private String requestId() {
        return com.jitong.im.platform.observability.RequestContextFilter.requestIdOrNull();
    }
}
