package com.jitong.im.platform.error;

import java.time.Instant;

public record ApiErrorResponse(
        int version,
        String code,
        String message,
        String requestId,
        Instant timestamp
) {
    static final int CURRENT_VERSION = 1;

    public static ApiErrorResponse create(ApiErrorDefinition definition, String requestId) {
        return new ApiErrorResponse(
                CURRENT_VERSION,
                definition.code(),
                definition.message(),
                requestId,
                Instant.now());
    }
}
