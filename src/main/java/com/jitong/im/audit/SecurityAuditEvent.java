package com.jitong.im.audit;

import com.jitong.im.platform.error.ApiErrorDefinition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SecurityAuditEvent(
        UUID id,
        SecurityAuditEventType type,
        AuditOutcome outcome,
        UUID actorUserId,
        UUID actorDeviceId,
        AuditSubjectType subjectType,
        UUID subjectId,
        UUID requestId,
        ApiErrorDefinition error,
        Instant occurredAt
) {
    public SecurityAuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if ((subjectType == null) != (subjectId == null)) {
            throw new IllegalArgumentException("subjectType and subjectId must be provided together");
        }
        if (requestId != null && requestId.version() != 4) {
            throw new IllegalArgumentException("requestId must be UUIDv4");
        }
    }
}
