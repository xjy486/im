package com.jitong.im.group;

import java.util.List;
import java.util.UUID;

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
