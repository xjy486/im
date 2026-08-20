package com.jitong.im.sync;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class OutboxRepository {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<OutboxRecord> claimDue(int limit, Instant now) {
        Instant leaseUntil = now.plus(LEASE);
        return jdbc.sql("""
                        WITH due AS (
                            SELECT id
                            FROM outbox
                            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
                               OR (status = 'PROCESSING' AND next_attempt_at <= :now)
                            ORDER BY created_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        UPDATE outbox o
                        SET status = 'PROCESSING',
                            attempt_count = o.attempt_count + 1,
                            next_attempt_at = :leaseUntil
                        FROM due
                        WHERE o.id = due.id
                        RETURNING o.id, o.event_type, o.entity_id, o.conversation_id,
                                  o.sync_seq, o.target_device_id, o.attempt_count, o.next_attempt_at
                        """)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("leaseUntil", utc(leaseUntil), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query((row, rowNum) -> new OutboxRecord(
                        row.getObject("id", UUID.class),
                        row.getString("event_type"),
                        row.getObject("entity_id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getLong("sync_seq"),
                        row.getObject("target_device_id", UUID.class),
                        row.getInt("attempt_count"),
                        row.getObject("next_attempt_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    void complete(UUID id, Instant now) {
        jdbc.sql("""
                        UPDATE outbox
                        SET status = 'COMPLETED',
                            completed_at = :completedAt,
                            next_attempt_at = :completedAt
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("id", id)
                .param("completedAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    void retry(UUID id, Instant nextAttemptAt) {
        jdbc.sql("""
                        UPDATE outbox
                        SET status = 'PENDING',
                            next_attempt_at = :nextAttemptAt
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("id", id)
                .param("nextAttemptAt", utc(nextAttemptAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
