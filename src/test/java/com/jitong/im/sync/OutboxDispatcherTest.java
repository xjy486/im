package com.jitong.im.sync;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void completes_delivered_rows_and_requeues_failed_rows() {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxDelivery delivery = mock(OutboxDelivery.class);
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                repository,
                delivery,
                Clock.fixed(NOW, ZoneOffset.UTC));
        OutboxRecord delivered = record();
        OutboxRecord failed = record();
        when(repository.claimDue(64, NOW)).thenReturn(List.of(delivered, failed));
        when(delivery.deliver(delivered)).thenReturn(true);
        when(delivery.deliver(failed)).thenReturn(false);

        dispatcher.dispatchDue();

        verify(repository).complete(delivered.id(), NOW);
        verify(repository).retry(eq(failed.id()), eq(NOW.plusSeconds(1)));
    }

    private OutboxRecord record() {
        return new OutboxRecord(
                UUID.randomUUID(),
                "MESSAGE_CREATED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                1,
                NOW);
    }
}
