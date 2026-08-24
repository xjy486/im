package com.jitong.im.auth;

import com.jitong.im.platform.observability.RequestContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController {

    private final AdminApiKeyVerifier adminApiKeyVerifier;
    private final AuthService authService;

    AdminUserController(
            AdminApiKeyVerifier adminApiKeyVerifier,
            AuthService authService
    ) {
        this.adminApiKeyVerifier = adminApiKeyVerifier;
        this.authService = authService;
    }

    @PostMapping
    PresetUserResponse create(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @Valid @RequestBody CreatePresetUserRequest request
    ) {
        requireAdmin(apiKey);
        return PresetUserResponse.from(authService.createPresetUser(
                request.displayName(),
                request.password()));
    }

    @PostMapping("/{userId}/retire")
    ResponseEntity<Void> retire(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @PathVariable UUID userId,
            HttpServletRequest request
    ) {
        requireAdmin(apiKey);
        authService.retireUser(
                userId,
                UUID.fromString(RequestContextFilter.requestId(request)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/password-reset")
    PasswordResetResponse resetPassword(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @PathVariable UUID userId,
            HttpServletRequest servletRequest
    ) {
        requireAdmin(apiKey);
        return authService.resetPassword(
                userId,
                UUID.fromString(RequestContextFilter.requestId(servletRequest)));
    }

    private void requireAdmin(String apiKey) {
        adminApiKeyVerifier.requireValid(apiKey);
    }
}
