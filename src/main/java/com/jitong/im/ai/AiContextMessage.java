package com.jitong.im.ai;

import java.util.UUID;

public record AiContextMessage(
        UUID messageId,
        long conversationSeq,
        UUID senderId,
        String type,
        String text,
        UUID mediaId,
        String mediaSha256
) {

    public AiContextMessage {
        type = type == null ? "TEXT" : type;
    }

    public AiContextMessage(
            UUID messageId,
            long conversationSeq,
            UUID senderId,
            String text
    ) {
        this(messageId, conversationSeq, senderId, "TEXT", text, null, null);
    }

    boolean hasAuthorizedImageReference() {
        return "IMAGE".equals(type) && mediaId != null && mediaSha256 != null;
    }
}
