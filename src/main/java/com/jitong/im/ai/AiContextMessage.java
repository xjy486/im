package com.jitong.im.ai;

import java.util.UUID;

public record AiContextMessage(
        UUID messageId,
        long conversationSeq,
        UUID senderId,
        String text
) {
}
