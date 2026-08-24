package com.jitong.im.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
class AuditLogRepository {

    private final JdbcClient jdbc;

    AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<AuditLogRecord> find(
            String eventType,
            String outcome,
            UUID actorUserId,
            AuditSubjectType subjectType,
            UUID subjectId,
            UUID requestId,
            Instant occurredBefore,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_type, outcome, actor_user_id, actor_device_id,
                       subject_type, subject_id, request_id, error_code, occurred_at
                FROM audit_logs
                WHERE 1 = 1
                """);
        if (eventType != null) {
            sql.append(" AND event_type = :eventType");
        }
        if (outcome != null) {
            sql.append(" AND outcome = :outcome");
        }
        if (actorUserId != null) {
            sql.append(" AND actor_user_id = :actorUserId");
        }
        if (subjectType != null) {
            sql.append(" AND subject_type = :subjectType");
        }
        if (subjectId != null) {
            sql.append(" AND subject_id = :subjectId");
        }
        if (requestId != null) {
            sql.append(" AND request_id = :requestId");
        }
        if (occurredBefore != null) {
            sql.append(" AND occurred_at < :occurredBefore");
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT :limit");

        var statement = jdbc.sql(sql.toString());
        if (eventType != null) {
            statement.param("eventType", eventType);
        }
        if (outcome != null) {
            statement.param("outcome", outcome);
        }
        if (actorUserId != null) {
            statement.param("actorUserId", actorUserId, Types.OTHER);
        }
        if (subjectType != null) {
            statement.param("subjectType", subjectType.name());
        }
        if (subjectId != null) {
            statement.param("subjectId", subjectId, Types.OTHER);
        }
        if (requestId != null) {
            statement.param("requestId", requestId, Types.OTHER);
        }
        if (occurredBefore != null) {
            statement.param("occurredBefore", OffsetDateTime.ofInstant(
                    occurredBefore,
                    java.time.ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        }
        return statement
                .param("limit", limit)
                .query((row, rowNumber) -> new AuditLogRecord(
                        row.getObject("id", UUID.class),
                        row.getString("event_type"),
                        row.getString("outcome"),
                        row.getObject("actor_user_id", UUID.class),
                        row.getObject("actor_device_id", UUID.class),
                        subjectType(row.getString("subject_type")),
                        row.getObject("subject_id", UUID.class),
                        row.getObject("request_id", UUID.class),
                        row.getString("error_code"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private AuditSubjectType subjectType(String value) {
        return value == null ? null : AuditSubjectType.valueOf(value);
    }
}
