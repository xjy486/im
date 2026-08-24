package com.jitong.im.auth;

import com.jitong.im.platform.observability.RequestContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(authService.login(
                request.accountNo(),
                request.password(),
                clientIp(servletRequest),
                java.util.UUID.fromString(RequestContextFilter.requestId(servletRequest)),
                request.deviceClass(),
                request.installationId()));
    }

    @PostMapping("/device-replacement/confirm")
    ResponseEntity<LoginResponse> confirmReplacement(
            @Valid @RequestBody ReplacementConfirmationRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(authService.confirmReplacement(
                request.replacementChallenge(),
                java.util.UUID.fromString(RequestContextFilter.requestId(servletRequest))));
    }

    @PostMapping("/refresh")
    ResponseEntity<LoginResponse> refresh(
            @Valid @RequestBody RefreshRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(authService.refresh(
                request.refreshToken(),
                java.util.UUID.fromString(RequestContextFilter.requestId(servletRequest))));
    }

    @PostMapping("/validate")
    ResponseEntity<Void> validate(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.requireUser(authorization);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest servletRequest
    ) {
        authService.logout(
                authorization,
                java.util.UUID.fromString(RequestContextFilter.requestId(servletRequest)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/change")
    ResponseEntity<LoginResponse> changePassword(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody PasswordChangeRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(authService.changePassword(
                authorization,
                request.currentPassword(),
                request.newPassword(),
                java.util.UUID.fromString(RequestContextFilter.requestId(servletRequest))));
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
