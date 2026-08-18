package com.jitong.im.auth;

import java.time.Instant;
import java.util.UUID;

record RefreshTokenRecord(
        UUID tokenId,
        UUID sessionId,
        UUID deviceId,
        UUID userId,
        UUID familyId,
        String state,
        String sessionStatus,
        Instant expiresAt,
        String deviceTrustState
) {
}
