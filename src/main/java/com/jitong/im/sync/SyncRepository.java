package com.jitong.im.sync;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class SyncRepository {

    private final JdbcClient jdbc;

    SyncRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long currentHighWatermark(UUID userId) {
        return jdbc.sql("""
                        SELECT last_seq
                        FROM user_sync_counters
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    void ensureCounter(UUID userId) {
        jdbc.sql("""
                        INSERT INTO user_sync_counters (user_id, last_seq, min_available_seq)
                        VALUES (:userId, 0, 1)
                        ON CONFLICT (user_id) DO NOTHING
                        """)
                .param("userId", userId)
                .update();
    }

    long retainedWindowStart(UUID userId) {
        return jdbc.sql("""
                        SELECT COALESCE(min_available_seq, 1)
                        FROM user_sync_counters
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElse(1L);
    }

    List<SyncEventRecord> listEvents(UUID userId, long afterSeq, long untilSeq, int limit) {
        return jdbc.sql("""
                        SELECT sync_seq, event_type, entity_id, conversation_id, created_at
                        FROM user_sync_events
                        WHERE user_id = :userId
                          AND sync_seq > :afterSeq
                          AND sync_seq <= :untilSeq
                          AND sync_seq >= (
                              SELECT min_available_seq
                              FROM user_sync_counters
                              WHERE user_id = :userId
                          )
                        ORDER BY sync_seq ASC
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("afterSeq", afterSeq)
                .param("untilSeq", untilSeq)
                .param("limit", limit)
                .query((row, rowNum) -> new SyncEventRecord(
                        row.getLong("sync_seq"),
                        row.getString("event_type"),
                        row.getObject("entity_id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    List<UUID> activeDeviceIds(UUID userId) {
        return jdbc.sql("""
                        SELECT id
                        FROM devices
                        WHERE user_id = :userId
                          AND trust_state = 'ACTIVE'
                        ORDER BY id
                        """)
                .param("userId", userId)
                .query(UUID.class)
                .list();
    }

    boolean isActiveDevice(UUID userId, UUID deviceId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM devices
                            WHERE id = :deviceId
                              AND user_id = :userId
                              AND trust_state = 'ACTIVE'
                        )
                        """)
                .param("deviceId", deviceId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    void acknowledge(UUID userId, UUID deviceId, long syncSeq) {
        jdbc.sql("""
                        INSERT INTO device_sync_states (device_id, user_id, last_acked_seq, updated_at)
                        VALUES (:deviceId, :userId, :syncSeq, CURRENT_TIMESTAMP)
                        ON CONFLICT (device_id)
                        DO UPDATE SET last_acked_seq = GREATEST(device_sync_states.last_acked_seq, EXCLUDED.last_acked_seq),
                                      updated_at = EXCLUDED.updated_at
                        WHERE device_sync_states.user_id = EXCLUDED.user_id
                          AND EXISTS (
                              SELECT 1
                              FROM devices
                              WHERE devices.id = EXCLUDED.device_id
                                AND devices.user_id = EXCLUDED.user_id
                                AND devices.trust_state = 'ACTIVE'
                          )
                        """)
                .param("deviceId", deviceId)
                .param("userId", userId)
                .param("syncSeq", syncSeq)
                .update();
    }

    void ensureUserCounter(UUID userId) {
        ensureCounter(userId);
    }

    long nextUserSequence(UUID userId) {
        ensureUserCounter(userId);
        return jdbc.sql("""
                        UPDATE user_sync_counters
                        SET last_seq = last_seq + 1
                        WHERE user_id = :userId
                        RETURNING last_seq
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    void insertEvent(
            UUID userId,
            long syncSeq,
            String eventType,
            UUID entityId,
            UUID conversationId
    ) {
        jdbc.sql("""
                        INSERT INTO user_sync_events (
                            user_id, sync_seq, event_type, entity_id, conversation_id
                        ) VALUES (
                            :userId, :syncSeq, :eventType, :entityId, :conversationId
                        )
                        """)
                .param("userId", userId)
                .param("syncSeq", syncSeq)
                .param("eventType", eventType)
                .param("entityId", entityId)
                .param("conversationId", conversationId, Types.OTHER)
                .update();
    }

    void insertOutbox(
            UUID eventId,
            String eventType,
            UUID entityId,
            UUID conversationId,
            long syncSeq,
            UUID targetDeviceId
    ) {
        jdbc.sql("""
                        INSERT INTO outbox (
                            id, event_type, entity_id, conversation_id,
                            sync_seq, target_device_id
                        ) VALUES (
                            :id, :eventType, :entityId, :conversationId,
                            :syncSeq, :targetDeviceId
                        )
                        ON CONFLICT (target_device_id, event_type, entity_id, sync_seq)
                        DO NOTHING
                        """)
                .param("id", eventId)
                .param("eventType", eventType)
                .param("entityId", entityId)
                .param("conversationId", conversationId, Types.OTHER)
                .param("syncSeq", syncSeq)
                .param("targetDeviceId", targetDeviceId)
                .update();
    }

    void pruneExpiredEvents(Instant cutoff) {
        jdbc.sql("""
                        WITH expired AS (
                            SELECT user_id, MAX(sync_seq) AS last_expired_seq
                            FROM user_sync_events
                            WHERE created_at < :cutoff
                            GROUP BY user_id
                        )
                        UPDATE user_sync_counters counters
                        SET min_available_seq = GREATEST(
                                counters.min_available_seq,
                                expired.last_expired_seq + 1)
                        FROM expired
                        WHERE counters.user_id = expired.user_id
                        """)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        DELETE FROM user_sync_events
                        WHERE created_at < :cutoff
                        """)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }
}
