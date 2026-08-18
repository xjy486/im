package com.jitong.im.auth;

import java.time.Instant;

public record DeviceReplacementResponse(
        int version,
        String code,
        String message,
        String requestId,
        Instant timestamp,
        String replacementChallenge,
        String deviceClass
) {
}
