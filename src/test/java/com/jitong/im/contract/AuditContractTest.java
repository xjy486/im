package com.jitong.im.contract;

import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditContractTest extends ContractTestEnvironment {

    @Autowired
    private SecurityAuditSink auditSink;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void security_events_are_persisted_without_content_bearing_fields() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.fromString("4981bedd-cabe-4a11-83e8-b9f43e14731f");
        SecurityAuditEvent event = new SecurityAuditEvent(
                eventId,
                SecurityAuditEventType.LOGIN,
                AuditOutcome.REJECTED,
                null,
                null,
                null,
                null,
                requestId,
                ApiErrorDefinition.INVALID_REQUEST,
                Instant.parse("2026-08-18T03:00:00Z"));

        auditSink.record(event);

        String eventType = jdbc.sql("SELECT event_type FROM audit_logs WHERE id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
        List<String> columns = jdbc.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'audit_logs'
                        """)
                .query(String.class)
                .list();

        assertThat(eventType).isEqualTo("LOGIN");
        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "id",
                        "event_type",
                        "outcome",
                        "actor_user_id",
                        "actor_device_id",
                        "subject_type",
                        "subject_id",
                        "request_id",
                        "error_code",
                        "occurred_at");
    }
}
