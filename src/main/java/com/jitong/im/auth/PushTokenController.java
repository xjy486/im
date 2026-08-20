package com.jitong.im.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
class PushTokenController {

    private final AuthService authService;
    private final DevicePushTokenService pushTokenService;

    PushTokenController(AuthService authService, DevicePushTokenService pushTokenService) {
        this.authService = authService;
        this.pushTokenService = pushTokenService;
    }

    @PostMapping("/push-token")
    ResponseEntity<Void> update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody PushTokenRequest request
    ) {
        AuthenticatedDevice device = authService.requireAuthenticatedDevice(authorization);
        if (!"MOBILE".equals(device.deviceClass())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!pushTokenService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        pushTokenService.update(
                device.deviceId(),
                device.sessionId(),
                request.token(),
                request.tokenVersion());
        return ResponseEntity.noContent().build();
    }
}
