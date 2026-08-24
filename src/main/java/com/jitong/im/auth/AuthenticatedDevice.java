package com.jitong.im.auth;

import java.util.UUID;

public record AuthenticatedDevice(
        UUID userId,
        UUID deviceId,
        UUID sessionId,
        String deviceClass,
        boolean passwordMustChange
) {

    public AuthenticatedDevice(UUID userId, UUID deviceId, String deviceClass) {
        this(userId, deviceId, null, deviceClass, false);
    }
}
