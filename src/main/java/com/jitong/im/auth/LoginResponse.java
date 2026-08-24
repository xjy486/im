package com.jitong.im.auth;

import java.time.Instant;
import java.util.UUID;

public record LoginResponse(
        int version,
        UUID userId,
        String accountNo,
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        UUID deviceId,
        String deviceClass,
        boolean passwordMustChange
) {
    static LoginResponse of(
            User user,
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt,
            UUID deviceId,
            String deviceClass,
            boolean passwordMustChange
    ) {
        return new LoginResponse(
                1,
                user.id(),
                user.accountNo(),
                accessToken,
                refreshToken,
                accessTokenExpiresAt,
                refreshTokenExpiresAt,
                deviceId,
                deviceClass,
                passwordMustChange);
    }
}
