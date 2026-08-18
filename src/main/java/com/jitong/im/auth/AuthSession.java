package com.jitong.im.auth;

import java.time.Instant;
import java.util.UUID;

record AuthSession(
        UUID userId,
        Instant expiresAt,
        String status
) {
}
