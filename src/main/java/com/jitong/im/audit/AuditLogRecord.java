package com.jitong.im.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditLogRecord(
        UUID id,
        String eventType,
        String outcome,
        UUID actorUserId,
        UUID actorDeviceId,
        AuditSubjectType subjectType,
        UUID subjectId,
        UUID requestId,
        String errorCode,
        Instant occurredAt
) {
}
