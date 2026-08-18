package com.jitong.im.auth;

import java.time.Instant;
import java.util.UUID;

record TokenPair(
        UUID deviceId,
        String deviceClass,
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt
) {
}
