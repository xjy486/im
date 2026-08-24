package com.jitong.im.ai;

import com.jitong.im.auth.AuthService;
import com.jitong.im.auth.AuthenticatedDevice;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class AiController {

    private final AuthService authService;
    private final AiService service;
    private final AiJobQueryService jobQueryService;

    AiController(
            AuthService authService,
            AiService service,
            AiJobQueryService jobQueryService
    ) {
        this.authService = authService;
        this.service = service;
        this.jobQueryService = jobQueryService;
    }

    @GetMapping("/conversations/{conversationId}/ai/consent")
    AiConsentResponse consent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID conversationId
    ) {
        return service.consent(authService.requireUserId(authorization), conversationId);
    }

    @PatchMapping("/conversations/{conversationId}/ai/consent")
    AiConsentResponse updateConsent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID conversationId,
            @Valid @RequestBody AiConsentUpdate request,
            HttpServletRequest servletRequest
    ) {
        return service.updateConsent(
                authService.requireUserId(authorization),
                conversationId,
                request.enabled(),
                UUID.fromString(RequestContextFilter.requestId(servletRequest)));
    }

    @PostMapping("/conversations/{conversationId}/ai/summary")
    AiJobResponse summary(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID conversationId,
            @Valid @RequestBody AiSummaryRequest request
    ) {
        AuthenticatedDevice device = authService.requireAuthenticatedDevice(authorization);
        return service.enqueueSummary(device, conversationId, request);
    }

    @PostMapping("/conversations/{conversationId}/ai/smart-replies")
    AiJobResponse smartReplies(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID conversationId,
            @Valid @RequestBody AiSmartReplyRequest request
    ) {
        AuthenticatedDevice device = authService.requireAuthenticatedDevice(authorization);
        return service.enqueueSmartReplies(device, conversationId, request);
    }

    @PostMapping("/conversations/{conversationId}/ai/extract")
    AiJobResponse extraction(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID conversationId,
            @Valid @RequestBody AiExtractionRequest request
    ) {
        AuthenticatedDevice device = authService.requireAuthenticatedDevice(authorization);
        return service.enqueueExtraction(device, conversationId, request);
    }

    @GetMapping("/ai/jobs/{jobId}")
    AiJobResponse job(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID jobId
    ) {
        return service.job(authService.requireUserId(authorization), jobId);
    }

    @GetMapping("/ai/jobs")
    List<AiJobStatusResponse> jobs(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return jobQueryService.listActiveForOwner(authService.requireUserId(authorization));
    }

    @GetMapping("/ai/artifacts")
    List<AiArtifactResponse> artifacts(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.artifacts(authService.requireUserId(authorization));
    }

    @GetMapping("/ai/action-items")
    List<AiActionItemResponse> actionItems(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.actionItems(authService.requireUserId(authorization));
    }

    @PatchMapping("/ai/action-items/{actionItemId}")
    AiActionItemResponse updateActionItem(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID actionItemId,
            @Valid @RequestBody AiActionItemUpdate request
    ) {
        return service.updateActionItem(
                authService.requireUserId(authorization),
                actionItemId,
                request);
    }

    @DeleteMapping("/ai/action-items/{actionItemId}")
    ResponseEntity<Void> deleteActionItem(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID actionItemId
    ) {
        service.deleteActionItem(authService.requireUserId(authorization), actionItemId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/ai/jobs/{jobId}")
    ResponseEntity<Void> deleteJob(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID jobId
    ) {
        service.deleteJob(authService.requireUserId(authorization), jobId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/ai/artifacts/{artifactId}")
    ResponseEntity<Void> deleteArtifact(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID artifactId
    ) {
        service.deleteArtifact(authService.requireUserId(authorization), artifactId);
        return ResponseEntity.noContent().build();
    }
}
