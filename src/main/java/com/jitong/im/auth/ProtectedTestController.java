package com.jitong.im.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/protected")
class ProtectedTestController {

    private final AuthService authService;

    ProtectedTestController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/test")
    ResponseEntity<Map<String, Object>> test(@RequestHeader(value = "Authorization", required = false) String authorization) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(Map.of(
                "version", 1,
                "authenticated", true,
                "userId", user.id()));
    }
}
