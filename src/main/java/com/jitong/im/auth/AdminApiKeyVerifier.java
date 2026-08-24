package com.jitong.im.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminApiKeyVerifier {

    private final AdminProperties adminProperties;

    public AdminApiKeyVerifier(AdminProperties adminProperties) {
        this.adminProperties = adminProperties;
    }

    public void requireValid(String apiKey) {
        if (!matches(apiKey, adminProperties.apiKey())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private boolean matches(String candidate, String expected) {
        return candidate != null
                && !candidate.isBlank()
                && expected != null
                && !expected.isBlank()
                && MessageDigest.isEqual(
                        candidate.getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8));
    }
}
