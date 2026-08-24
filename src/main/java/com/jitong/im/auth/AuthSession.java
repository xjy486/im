package com.jitong.im.auth;

import java.time.Instant;
import java.util.UUID;

record AuthSession(
        UUID sessionId,
        UUID userId,
        UUID deviceId,
        Instant expiresAt,
        String status,
        String deviceTrustState,
        boolean passwordMustChange
) {
}
