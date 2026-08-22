package com.jitong.im.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record MessageRecord(
        UUID messageId,
        UUID conversationId,
        UUID senderId,
        UUID clientMsgId,
        long conversationSeq,
        String type,
        String state,
        String text,
        UUID mediaId,
        Instant serverAcceptedAt,
        Instant recalledAt,
        String systemEventType,
        UUID systemTargetUserId,
        String systemRole,
        UUID moderatedByUserId,
        String moderatedReason,
        Instant moderatedAt
) {
    MessageRecord(
            UUID messageId,
            UUID conversationId,
            UUID senderId,
            UUID clientMsgId,
            long conversationSeq,
            String type,
            String state,
            String text,
            UUID mediaId,
            Instant serverAcceptedAt,
            Instant recalledAt
    ) {
        this(
                messageId,
                conversationId,
                senderId,
                clientMsgId,
                conversationSeq,
                type,
                state,
                text,
                mediaId,
                serverAcceptedAt,
                recalledAt,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    MessageRecord(
            UUID messageId,
            UUID conversationId,
            UUID senderId,
            UUID clientMsgId,
            long conversationSeq,
            String type,
            String state,
            String text,
            Instant serverAcceptedAt
    ) {
        this(
                messageId,
                conversationId,
                senderId,
                clientMsgId,
                conversationSeq,
                type,
                state,
                text,
                null,
                serverAcceptedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    MessageRecord(
            UUID messageId,
            UUID conversationId,
            UUID senderId,
            UUID clientMsgId,
            long conversationSeq,
            String type,
            String state,
            String text,
            UUID mediaId,
            Instant serverAcceptedAt
    ) {
        this(
                messageId,
                conversationId,
                senderId,
                clientMsgId,
                conversationSeq,
                type,
                state,
                text,
                mediaId,
                serverAcceptedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}

record ConversationMessagePage(
        int version,
        UUID conversationId,
        List<MessageRecord> messages
) {
}

record MessageSendResult(
        MessageRecord message,
        boolean created
) {
}

record ModerateMessageRequest(
        String reason
) {
}
