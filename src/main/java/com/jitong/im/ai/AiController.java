package com.jitong.im.ai;

import com.jitong.im.auth.AuthService;
import com.jitong.im.auth.AuthenticatedDevice;
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

    AiController(AuthService authService, AiService service) {
        this.authService = authService;
        this.service = service;
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
            @Valid @RequestBody AiConsentUpdate request
    ) {
        return service.updateConsent(
                authService.requireUserId(authorization),
                conversationId,
                request.enabled());
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

    @GetMapping("/ai/jobs/{jobId}")
    AiJobResponse job(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID jobId
    ) {
        return service.job(authService.requireUserId(authorization), jobId);
    }

    @GetMapping("/ai/artifacts")
    List<AiArtifactResponse> artifacts(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.artifacts(authService.requireUserId(authorization));
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
