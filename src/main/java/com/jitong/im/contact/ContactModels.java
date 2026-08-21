package com.jitong.im.contact;

import java.time.Instant;
import java.util.UUID;

record ContactSearchResult(
        int version,
        String accountNo,
        String displayName,
        String avatarUrl,
        long avatarVersion,
        String avatarFallback,
        String relationship,
        String pendingRequestId
) {
}

record ContactRequestResponse(
        int version,
        UUID requestId,
        UUID requesterId,
        UUID recipientId,
        String status,
        String verification,
        Instant expiresAt,
        UUID conversationId
) {
}

record ContactRequestSummary(
        int version,
        UUID requestId,
        UUID requesterId,
        UUID recipientId,
        String status,
        String verification,
        Instant expiresAt,
        boolean incoming,
        String peerAccountNo,
        String peerDisplayName
) {
}

record ContactSummary(
        int version,
        UUID userId,
        String accountNo,
        String displayName,
        UUID conversationId,
        String relationship,
        String avatarUrl,
        long avatarVersion,
        String avatarFallback
) {
}

record ConversationSummary(
        int version,
        UUID conversationId,
        UUID peerUserId,
        String peerAccountNo,
        String peerDisplayName,
        String status,
        String relationship,
        boolean blockedByMe,
        long readSeq,
        long peerReadSeq,
        String avatarUrl,
        long avatarVersion,
        String avatarFallback,
        boolean searchVisible,
        long searchVisibleAfterSeq
) {
}
