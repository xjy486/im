package com.jitong.im.sync;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
class OutboxDispatcher {

    private static final int BATCH_SIZE = 64;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final OutboxRepository repository;
    private final OutboxDelivery delivery;
    private final Clock clock;

    @Autowired
    OutboxDispatcher(OutboxRepository repository, OutboxDelivery delivery) {
        this(repository, delivery, Clock.systemUTC());
    }

    OutboxDispatcher(OutboxRepository repository, OutboxDelivery delivery, Clock clock) {
        this.repository = repository;
        this.delivery = delivery;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${jitong.outbox.poll-interval:250}")
    void dispatchDue() {
        Instant now = clock.instant();
        List<OutboxRecord> records = repository.claimDue(BATCH_SIZE, now);
        for (OutboxRecord record : records) {
            if (delivery.deliver(record)) {
                repository.complete(record.id(), now);
            } else {
                repository.retry(record.id(), now.plus(RETRY_DELAY));
            }
        }
    }
}
