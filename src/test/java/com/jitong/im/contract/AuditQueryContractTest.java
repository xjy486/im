package com.jitong.im.contract;

import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.AuditSubjectType;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditQueryContractTest extends ContractTestEnvironment {

    private static final String ADMIN_API_KEY = "contract-test-admin-key";
    private static final UUID ACTOR_USER_ID =
            UUID.fromString("0f3e8c50-e3b3-4f4d-92cf-f4a2dd0cb1b9");
    private static final UUID SUBJECT_ID =
            UUID.fromString("b0c7a1d8-75d1-4a4b-a1e1-50e9ce82dc40");
    private static final UUID REQUEST_ID =
            UUID.fromString("8e2c47d5-7f5f-4e0d-8a40-4056fd33dc9a");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private SecurityAuditSink auditSink;

    @Test
    void administrators_can_query_content_free_audit_metadata() {
        auditSink.record(new SecurityAuditEvent(
                UUID.randomUUID(),
                SecurityAuditEventType.TOKEN_REPLAY,
                AuditOutcome.REJECTED,
                ACTOR_USER_ID,
                null,
                AuditSubjectType.DEVICE,
                SUBJECT_ID,
                REQUEST_ID,
                null,
                Instant.parse("2026-08-24T08:00:00Z")));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Api-Key", ADMIN_API_KEY);
        ResponseEntity<List> response = http.exchange(
                "/api/v1/admin/audit-logs?eventType=TOKEN_REPLAY&requestId="
                        + REQUEST_ID
                        + "&limit=10",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        Map<String, Object> event = (Map<String, Object>) response.getBody().get(0);
        assertThat(event)
                .containsEntry("eventType", "TOKEN_REPLAY")
                .containsEntry("outcome", "REJECTED")
                .containsEntry("actorUserId", ACTOR_USER_ID.toString())
                .containsEntry("subjectType", "DEVICE")
                .containsEntry("subjectId", SUBJECT_ID.toString())
                .containsEntry("requestId", REQUEST_ID.toString())
                .doesNotContainKeys("password", "token", "message", "mediaUrl", "content");
    }

    @Test
    void audit_log_queries_require_the_protected_administrator_surface() {
        ResponseEntity<Map> response = http.getForEntity(
                "/api/v1/admin/audit-logs",
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "FORBIDDEN");
    }
}

