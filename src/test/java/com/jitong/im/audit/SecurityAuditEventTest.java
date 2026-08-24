package com.jitong.im.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityAuditEventTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("4981bedd-cabe-4a11-83e8-b9f43e14731f");

    @AfterEach
    void clearRequestContext() {
        MDC.clear();
    }

    @Test
    void inherits_the_http_request_id_when_the_caller_does_not_supply_one() {
        MDC.put("requestId", REQUEST_ID.toString());

        SecurityAuditEvent event = new SecurityAuditEvent(
                UUID.randomUUID(),
                SecurityAuditEventType.LOGIN,
                AuditOutcome.SUCCEEDED,
                null,
                null,
                null,
                null,
                null,
                null,
                java.time.Instant.EPOCH);

        assertThat(event.requestId()).isEqualTo(REQUEST_ID);
    }
}

