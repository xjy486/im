package com.jitong.im.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController {

    private final AdminProperties adminProperties;
    private final AuthService authService;

    AdminUserController(AdminProperties adminProperties, AuthService authService) {
        this.adminProperties = adminProperties;
        this.authService = authService;
    }

    @PostMapping
    PresetUserResponse create(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @Valid @RequestBody CreatePresetUserRequest request
    ) {
        if (!matchesApiKey(apiKey, adminProperties.apiKey())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return PresetUserResponse.from(authService.createPresetUser(
                request.displayName(),
                request.password()));
    }

    private boolean matchesApiKey(String candidate, String expected) {
        return candidate != null
                && !candidate.isBlank()
                && expected != null
                && !expected.isBlank()
                && MessageDigest.isEqual(
                        candidate.getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8));
    }
}
