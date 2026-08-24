package com.jitong.im.abuse;

import com.jitong.im.auth.AuthCredentialsRevokedEvent;
import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.AuditSubjectType;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import com.jitong.im.auth.UuidV7;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
class AdminAbuseService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final AbuseRepository repository;
    private final SecurityAuditSink auditSink;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    AdminAbuseService(
            AbuseRepository repository,
            SecurityAuditSink auditSink,
            ApplicationEventPublisher eventPublisher
    ) {
        this(repository, auditSink, eventPublisher, Clock.systemUTC());
    }

    AdminAbuseService(
            AbuseRepository repository,
            SecurityAuditSink auditSink,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.repository = repository;
        this.auditSink = auditSink;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<AbuseReportResponse> listReports(String rawStatus, Integer requestedLimit) {
        String status = normalizeStatus(rawStatus);
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new AbuseException(ApiErrorDefinition.INVALID_REQUEST);
        }
        return repository.listForAdmin(status, limit).stream()
                .map(AbuseReportResponse::from)
                .toList();
    }

    @Transactional
    AbuseReportResponse updateReport(
            UUID reportId,
            AbuseReportStatusUpdateRequest request,
            UUID requestId
    ) {
        if (request == null || request.status() == null) {
            throw new AbuseException(ApiErrorDefinition.INVALID_REQUEST);
        }
        String status = normalizeStatus(request.status());
        if (status == null) {
            throw new AbuseException(ApiErrorDefinition.INVALID_REQUEST);
        }
        AbuseRepository.AbuseReportRecord report = repository.findReport(reportId);
        if (report == null) {
            throw new AbuseException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        if (!isAllowedTransition(report.status(), status)) {
            throw new AbuseException(ApiErrorDefinition.CONFLICT);
        }
        if (!report.status().equals(status)
                && repository.updateReportStatus(
                        reportId,
                        report.status(),
                        status,
                        clock.instant()) != 1) {
            throw new AbuseException(ApiErrorDefinition.CONFLICT);
        }
        AbuseRepository.AbuseReportRecord updated = repository.findReport(reportId);
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.ABUSE_REPORT_REVIEWED,
                AuditOutcome.SUCCEEDED,
                null,
                null,
                AuditSubjectType.REPORT,
                reportId,
                requestId,
                null,
                clock.instant()));
        return AbuseReportResponse.from(updated);
    }

    @Transactional
    void suspendUser(UUID userId, String rawReason, UUID requestId) {
        String status = repository.userStatus(userId);
        if (status == null || "DELETED".equals(status)) {
            throw new AbuseException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        if ("SUSPENDED".equals(status)) {
            auditSink.record(new SecurityAuditEvent(
                    UuidV7.random(),
                    SecurityAuditEventType.USER_SUSPENSION,
                    AuditOutcome.SUCCEEDED,
                    null,
                    null,
                    AuditSubjectType.USER,
                    userId,
                    requestId,
                    null,
                    clock.instant()));
            return;
        }
        Set<UUID> activeDeviceIds = Set.copyOf(repository.activeDeviceIds(userId));
        Instant now = clock.instant();
        repository.suspendUser(userId, normalizeReasonCode(rawReason), now);
        repository.revokeUserCredentials(userId, now);
        if (!activeDeviceIds.isEmpty()) {
            eventPublisher.publishEvent(new AuthCredentialsRevokedEvent(activeDeviceIds));
        }
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.USER_SUSPENSION,
                AuditOutcome.SUCCEEDED,
                null,
                null,
                AuditSubjectType.USER,
                userId,
                requestId,
                null,
                now));
    }

    @Transactional
    void restoreUser(UUID userId, UUID requestId) {
        String status = repository.userStatus(userId);
        if (status == null || "DELETED".equals(status)) {
            throw new AbuseException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        if ("ACTIVE".equals(status)) {
            return;
        }
        repository.restoreUser(userId);
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.USER_SUSPENSION,
                AuditOutcome.SUCCEEDED,
                null,
                null,
                AuditSubjectType.USER,
                userId,
                requestId,
                null,
                clock.instant()));
    }

    @Transactional
    void suspendGroup(UUID conversationId, String rawReason, UUID requestId) {
        String status = repository.groupStatus(conversationId);
        if (status == null) {
            throw new AbuseException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        if (!"ACTIVE".equals(status)
                || !"PUBLIC".equals(repository.groupVisibility(conversationId))) {
            throw new AbuseException(ApiErrorDefinition.CONFLICT);
        }
        repository.suspendGroup(conversationId, normalizeReasonCode(rawReason), clock.instant());
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.GROUP_SUSPENSION,
                AuditOutcome.SUCCEEDED,
                null,
                null,
                AuditSubjectType.GROUP,
                conversationId,
                requestId,
                null,
                clock.instant()));
    }

    @Transactional
    void restoreGroup(UUID conversationId, UUID requestId) {
        String status = repository.groupStatus(conversationId);
        if (status == null) {
            throw new AbuseException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        if (!"ACTIVE".equals(status)) {
            throw new AbuseException(ApiErrorDefinition.CONFLICT);
        }
        repository.restoreGroup(conversationId);
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.GROUP_SUSPENSION,
                AuditOutcome.SUCCEEDED,
                null,
                null,
                AuditSubjectType.GROUP,
                conversationId,
                requestId,
                null,
                clock.instant()));
    }

    private String normalizeStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return null;
        }
        String status = rawStatus.trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "OPEN", "REVIEWING", "RESOLVED", "DISMISSED" -> status;
            default -> throw new AbuseException(ApiErrorDefinition.INVALID_REQUEST);
        };
    }

    private String normalizeReasonCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "ADMIN_ACTION";
        }
        return reasonCode.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isAllowedTransition(String currentStatus, String nextStatus) {
        if (currentStatus.equals(nextStatus)) {
            return true;
        }
        return switch (currentStatus) {
            case "OPEN" -> nextStatus.equals("REVIEWING")
                    || nextStatus.equals("RESOLVED")
                    || nextStatus.equals("DISMISSED");
            case "REVIEWING" -> nextStatus.equals("RESOLVED")
                    || nextStatus.equals("DISMISSED");
            case "RESOLVED", "DISMISSED" -> false;
            default -> false;
        };
    }

}
