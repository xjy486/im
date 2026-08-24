package com.jitong.im.abuse;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

record AbuseReportCreateRequest(
        @NotNull
        String targetType,
        @NotNull
        UUID targetId,
        @Size(min = 1, max = 64)
        @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,63}")
        String reasonCode
) {
}

record AbuseReportStatusUpdateRequest(
        @NotNull
        @Pattern(regexp = "OPEN|REVIEWING|RESOLVED|DISMISSED")
        String status
) {
}

record PlatformSuspensionRequest(
        @Size(max = 500)
        String reason
) {
}

record AbuseReportResponse(
        int version,
        UUID reportId,
        UUID reporterUserId,
        String targetType,
        UUID targetId,
        String reasonCode,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt
) {
}
