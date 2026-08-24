package com.jitong.im.audit;

import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
class AuditLogQueryService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final AuditLogRepository repository;

    AuditLogQueryService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    List<AuditLogRecord> find(
            String rawEventType,
            String rawOutcome,
            UUID actorUserId,
            String rawSubjectType,
            UUID subjectId,
            UUID requestId,
            Instant occurredBefore,
            Integer requestedLimit
    ) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new AuditQueryException(ApiErrorDefinition.INVALID_REQUEST);
        }
        String eventType = normalizeEventType(rawEventType);
        String outcome = normalizeOutcome(rawOutcome);
        AuditSubjectType subjectType = normalizeSubjectType(rawSubjectType);
        if (subjectId != null && subjectType == null) {
            throw new AuditQueryException(ApiErrorDefinition.INVALID_REQUEST);
        }
        return repository.find(
                eventType,
                outcome,
                actorUserId,
                subjectType,
                subjectId,
                requestId,
                occurredBefore,
                limit);
    }

    private String normalizeEventType(String rawEventType) {
        if (rawEventType == null || rawEventType.isBlank()) {
            return null;
        }
        String value = rawEventType.trim().toUpperCase(Locale.ROOT);
        try {
            return SecurityAuditEventType.valueOf(value).name();
        } catch (IllegalArgumentException exception) {
            throw new AuditQueryException(ApiErrorDefinition.INVALID_REQUEST);
        }
    }

    private String normalizeOutcome(String rawOutcome) {
        if (rawOutcome == null || rawOutcome.isBlank()) {
            return null;
        }
        String value = rawOutcome.trim().toUpperCase(Locale.ROOT);
        try {
            return AuditOutcome.valueOf(value).name();
        } catch (IllegalArgumentException exception) {
            throw new AuditQueryException(ApiErrorDefinition.INVALID_REQUEST);
        }
    }

    private AuditSubjectType normalizeSubjectType(String rawSubjectType) {
        if (rawSubjectType == null || rawSubjectType.isBlank()) {
            return null;
        }
        try {
            return AuditSubjectType.valueOf(rawSubjectType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AuditQueryException(ApiErrorDefinition.INVALID_REQUEST);
        }
    }
}
