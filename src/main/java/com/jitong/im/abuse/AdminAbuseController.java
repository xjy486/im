package com.jitong.im.abuse;

import com.jitong.im.auth.AdminApiKeyVerifier;
import com.jitong.im.platform.observability.RequestContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
class AdminAbuseController {

    private final AdminApiKeyVerifier adminApiKeyVerifier;
    private final AdminAbuseService service;

    AdminAbuseController(
            AdminApiKeyVerifier adminApiKeyVerifier,
            AdminAbuseService service
    ) {
        this.adminApiKeyVerifier = adminApiKeyVerifier;
        this.service = service;
    }

    @GetMapping("/abuse-reports")
    List<AbuseReportResponse> listReports(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit
    ) {
        adminApiKeyVerifier.requireValid(apiKey);
        return service.listReports(status, limit);
    }

    @PatchMapping("/abuse-reports/{reportId}")
    AbuseReportResponse updateReport(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @PathVariable UUID reportId,
            @Valid @RequestBody AbuseReportStatusUpdateRequest request,
            HttpServletRequest servletRequest
    ) {
        adminApiKeyVerifier.requireValid(apiKey);
        return service.updateReport(
                reportId,
                request,
                UUID.fromString(RequestContextFilter.requestId(servletRequest)));
    }

    @PostMapping("/users/{userId}/suspension")
    ResponseEntity<Void> suspendUser(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @PathVariable UUID userId,
            @Valid @RequestBody(required = false) PlatformSuspensionRequest request,
            HttpServletRequest servletRequest
    ) {
        adminApiKeyVerifier.requireValid(apiKey);
        service.suspendUser(
                userId,
                request == null ? null : request.reasonCode(),
                UUID.fromString(RequestContextFilter.requestId(servletRequest)));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{userId}/suspension")
    ResponseEntity<Void> restoreUser(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @PathVariable UUID userId,
            HttpServletRequest servletRequest
    ) {
        adminApiKeyVerifier.requireValid(apiKey);
        service.restoreUser(
                userId,
                UUID.fromString(RequestContextFilter.requestId(servletRequest)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/groups/{conversationId}/suspension")
    ResponseEntity<Void> suspendGroup(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @PathVariable UUID conversationId,
            @Valid @RequestBody(required = false) PlatformSuspensionRequest request,
            HttpServletRequest servletRequest
    ) {
        adminApiKeyVerifier.requireValid(apiKey);
        service.suspendGroup(
                conversationId,
                request == null ? null : request.reasonCode(),
                UUID.fromString(RequestContextFilter.requestId(servletRequest)));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/groups/{conversationId}/suspension")
    ResponseEntity<Void> restoreGroup(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @PathVariable UUID conversationId,
            HttpServletRequest servletRequest
    ) {
        adminApiKeyVerifier.requireValid(apiKey);
        service.restoreGroup(
                conversationId,
                UUID.fromString(RequestContextFilter.requestId(servletRequest)));
        return ResponseEntity.noContent().build();
    }
}
