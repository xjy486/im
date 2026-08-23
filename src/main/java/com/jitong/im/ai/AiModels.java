package com.jitong.im.ai;

import java.time.Instant;
import java.util.UUID;

record AiConsentResponse(
        int version,
        UUID conversationId,
        UUID userId,
        boolean enabled,
        boolean enabledForBoth,
        long policyVersion
) {
}

record AiSummaryRequest(
        UUID requestId,
        Long afterSeq,
        Long untilSeq
) {
}

record AiJobResponse(
        int version,
        UUID jobId,
        UUID ownerUserId,
        UUID requestingDeviceId,
        UUID conversationId,
        UUID requestId,
        String kind,
        String status,
        long fromSeq,
        long toSeq,
        String model,
        String promptVersion,
        String errorCode,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant expiresAt,
        AiSummary result
) {
}

record AiArtifactResponse(
        int version,
        UUID artifactId,
        UUID jobId,
        String artifactType,
        AiSummary content,
        Instant createdAt,
        Instant expiresAt
) {
}

record AiJobRecord(
        UUID jobId,
        UUID ownerUserId,
        UUID requestingDeviceId,
        UUID conversationId,
        UUID requestId,
        String kind,
        String status,
        long fromSeq,
        long toSeq,
        String contextDigest,
        String contextJson,
        long aiPolicyVersion,
        String model,
        String promptVersion,
        String resultJson,
        String errorCode,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant expiresAt
) {
}

record AiConversation(
        UUID conversationId,
        UUID ownerUserId,
        UUID peerUserId,
        String status,
        long lastSeq,
        long ownerReadSeq,
        long policyVersion,
        boolean ownerConsent,
        boolean peerConsent
) {
    boolean enabledForBoth() {
        return ownerConsent && peerConsent;
    }
}
