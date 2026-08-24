package com.jitong.im.abuse;

import com.jitong.im.auth.AuthService;
import com.jitong.im.auth.UuidV7;
import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.AuditSubjectType;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
class AbuseReportService {

    private final AbuseRepository repository;
    private final AuthService authService;
    private final SecurityAuditSink auditSink;
    private final Clock clock;

    @Autowired
    AbuseReportService(
            AbuseRepository repository,
            AuthService authService,
            SecurityAuditSink auditSink
    ) {
        this(repository, authService, auditSink, Clock.systemUTC());
    }

    AbuseReportService(
            AbuseRepository repository,
            AuthService authService,
            SecurityAuditSink auditSink,
            Clock clock
    ) {
        this.repository = repository;
        this.authService = authService;
        this.auditSink = auditSink;
        this.clock = clock;
    }

    @Transactional
    AbuseReportResponse create(
            String authorization,
            AbuseReportCreateRequest request,
            UUID requestId
    ) {
        UUID reporterUserId = authService.requireUserId(authorization);
        if (request == null || request.targetType() == null
                || request.targetId() == null || request.reasonCode() == null) {
            throw new AbuseException(ApiErrorDefinition.INVALID_REQUEST);
        }
        String targetType = normalize(request.targetType());
        String reasonCode = normalize(request.reasonCode());
        validateTarget(reporterUserId, targetType, request.targetId());
        if (reasonCode.isBlank()) {
            throw new AbuseException(ApiErrorDefinition.INVALID_REQUEST);
        }
        AbuseRepository.AbuseReportRecord report = repository.insertReport(
                UuidV7.random(),
                reporterUserId,
                targetType,
                request.targetId(),
                reasonCode,
                clock.instant());
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.ABUSE_REPORT_CREATED,
                AuditOutcome.SUCCEEDED,
                reporterUserId,
                null,
                AuditSubjectType.REPORT,
                report.reportId(),
                requestId,
                null,
                clock.instant()));
        return AbuseReportResponse.from(report);
    }

    @Transactional(readOnly = true)
    List<AbuseReportResponse> listMine(String authorization) {
        UUID reporterUserId = authService.requireUserId(authorization);
        return repository.listForReporter(reporterUserId).stream()
                .map(AbuseReportResponse::from)
                .toList();
    }

    private void validateTarget(UUID reporterUserId, String targetType, UUID targetId) {
        switch (targetType) {
            case "USER" -> {
                if (reporterUserId.equals(targetId)
                        || !repository.targetUserExists(targetId)) {
                    throw new AbuseException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
                }
            }
            case "GROUP" -> {
                if (!repository.reportableGroupExists(targetId, reporterUserId)) {
                    throw new AbuseException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
                }
            }
            case "MESSAGE" -> {
                if (!repository.reportableMessageExists(targetId, reporterUserId)) {
                    throw new AbuseException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
                }
            }
            default -> throw new AbuseException(ApiErrorDefinition.INVALID_REQUEST);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

}
