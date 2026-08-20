package com.jitong.im.auth;

import java.util.UUID;

public record AuthenticatedDevice(
        UUID userId,
        UUID deviceId,
        String deviceClass
) {
}
