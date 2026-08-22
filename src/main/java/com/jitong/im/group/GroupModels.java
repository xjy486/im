package com.jitong.im.group;

import java.util.List;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record GroupCreateResponse(
        int version,
        UUID conversationId,
        String groupNo,
        String name,
        String description,
        String visibility,
        UUID ownerUserId,
        String role,
        String avatarUrl,
        long avatarVersion,
        int memberCount
) {
}

record GroupSummary(
        int version,
        UUID conversationId,
        String groupNo,
        String name,
        String description,
        String visibility,
        String role,
        String avatarUrl,
        long avatarVersion,
        int memberCount
) {
}

record GroupSearchResult(
        String name,
        String avatarUrl,
        String description,
        int memberCount
) {
}

record GroupSearchPage(
        int version,
        List<GroupSearchResult> groups
) {
}

record GroupMemberAddRequest(
        @Pattern(regexp = "[1-9][0-9]{10}")
        String accountNo
) {
}

record GroupMemberAddResponse(
        int version,
        UUID conversationId,
        UUID userId,
        String role,
        int memberCount
) {
}

record GroupRoleChangeRequest(
        @Pattern(regexp = "ADMIN|MEMBER")
        String role
) {
}

record GroupRoleChangeResponse(
        int version,
        UUID conversationId,
        UUID userId,
        String role
) {
}

record GroupOwnerTransferRequest(
        UUID userId
) {
}

record GroupOwnerTransferResponse(
        int version,
        UUID conversationId,
        UUID previousOwnerUserId,
        UUID ownerUserId
) {
}

record GroupProfileUpdateRequest(
        @Size(min = 1, max = 128)
        String name,
        @Size(max = 1000)
        String description,
        @Pattern(regexp = "PUBLIC|UNLISTED|PRIVATE")
        String visibility
) {
}

record GroupInviteCreateRequest(
        @Min(1)
        @Max(10000)
        Integer maxUses,
        @Min(60)
        @Max(2592000)
        Long expiresInSeconds
) {
}

record GroupInviteResponse(
        int version,
        UUID inviteId,
        UUID conversationId,
        int maxUses,
        int useCount,
        Instant expiresAt,
        String deepLink,
        String qrPayload
) {
}

record GroupInviteResolveResponse(
        int version,
        UUID conversationId,
        String groupNo,
        String name,
        String description,
        String visibility,
        String avatarUrl,
        long avatarVersion,
        int memberCount,
        Instant expiresAt
) {
}

record GroupJoinRequestCreateRequest(
        @Size(min = 20, max = 128)
        String inviteToken
) {
}

record GroupJoinRequestResponse(
        int version,
        UUID requestId,
        UUID conversationId,
        UUID userId,
        String status,
        UUID inviteId,
        Instant createdAt,
        Instant resolvedAt
) {
}

record GroupJoinRequestSummary(
        int version,
        UUID requestId,
        UUID conversationId,
        UUID userId,
        String accountNo,
        String displayName,
        String status,
        UUID inviteId,
        Instant createdAt,
        Instant resolvedAt
) {
}

record GroupBanRequest(
        @Size(max = 500)
        String reason
) {
}
