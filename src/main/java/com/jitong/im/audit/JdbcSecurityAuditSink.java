package com.jitong.im.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
class JdbcSecurityAuditSink implements SecurityAuditSink {

    private final JdbcClient jdbc;

    JdbcSecurityAuditSink(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(SecurityAuditEvent event) {
        jdbc.sql("""
                        INSERT INTO audit_logs (
                            id,
                            event_type,
                            outcome,
                            actor_user_id,
                            actor_device_id,
                            subject_type,
                            subject_id,
                            request_id,
                            error_code,
                            occurred_at
                        ) VALUES (
                            :id,
                            :eventType,
                            :outcome,
                            :actorUserId,
                            :actorDeviceId,
                            :subjectType,
                            :subjectId,
                            :requestId,
                            :errorCode,
                            :occurredAt
                        )
                        """)
                .param("id", event.id())
                .param("eventType", event.type().name())
                .param("outcome", event.outcome().name())
                .param("actorUserId", event.actorUserId(), Types.OTHER)
                .param("actorDeviceId", event.actorDeviceId(), Types.OTHER)
                .param("subjectType", event.subjectType() == null ? null : event.subjectType().name(), Types.VARCHAR)
                .param("subjectId", event.subjectId(), Types.OTHER)
                .param("requestId", event.requestId(), Types.OTHER)
                .param("errorCode", event.error() == null ? null : event.error().code(), Types.VARCHAR)
                .param(
                        "occurredAt",
                        OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }
}
