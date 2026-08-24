package com.jitong.im.ai;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

record AiSmartReplyRequest(
        UUID requestId
) {
}

record AiExtractionRequest(
        UUID requestId,
        List<UUID> messageIds
) {
    AiExtractionRequest {
        messageIds = messageIds == null ? null : List.copyOf(messageIds);
    }
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
        Object result
) {
}

record AiArtifactResponse(
        int version,
        UUID artifactId,
        UUID jobId,
        UUID conversationId,
        String artifactType,
        Object content,
        Instant createdAt,
        Instant expiresAt
) {
}

record AiActionItemUpdate(
        String status
) {
}

record AiActionItemResponse(
        int version,
        UUID actionItemId,
        UUID sourceJobId,
        UUID ownerUserId,
        UUID conversationId,
        UUID assigneeUserId,
        String title,
        String details,
        Instant dueAt,
        String priority,
        double confidence,
        List<UUID> sourceMessageIds,
        String status,
        Instant createdAt,
        Instant completedAt
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
        long membershipVersion,
        String cacheKey,
        LocalDate budgetDate,
        long reservedTokens,
        int attemptCount,
        int inputTokens,
        int outputTokens,
        String model,
        String promptVersion,
        boolean imageInputEnabled,
        String resultJson,
        String errorCode,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant expiresAt
) {
}

record AiCacheEntry(
        String cacheKey,
        String resultJson,
        Instant expiresAt
) {
}

record AiConversation(
        UUID conversationId,
        UUID ownerUserId,
        UUID peerUserId,
        String type,
        String status,
        long lastSeq,
        long ownerReadSeq,
        long policyVersion,
        long membershipVersion,
        long historyVisibleAfterSeq,
        boolean aiEnabled,
        boolean ownerConsent
) {
}
