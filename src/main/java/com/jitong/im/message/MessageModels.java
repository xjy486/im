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
        Instant serverAcceptedAt
) {
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
