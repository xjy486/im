package com.jitong.im.audit;

import com.jitong.im.auth.AdminApiKeyVerifier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
class AuditLogController {

    private final AdminApiKeyVerifier adminApiKeyVerifier;
    private final AuditLogQueryService service;

    AuditLogController(
            AdminApiKeyVerifier adminApiKeyVerifier,
            AuditLogQueryService service
    ) {
        this.adminApiKeyVerifier = adminApiKeyVerifier;
        this.service = service;
    }

    @GetMapping
    List<AuditLogRecord> list(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) UUID requestId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurredBefore,
            @RequestParam(required = false) Integer limit
    ) {
        adminApiKeyVerifier.requireValid(apiKey);
        return service.find(
                eventType,
                outcome,
                actorUserId,
                subjectType,
                subjectId,
                requestId,
                occurredBefore,
                limit);
    }
}
