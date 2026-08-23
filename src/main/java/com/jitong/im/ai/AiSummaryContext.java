package com.jitong.im.ai;

import java.util.List;
import java.util.UUID;

public record AiSummaryContext(
        UUID conversationId,
        List<AiContextMessage> messages
) {
    public AiSummaryContext {
        messages = List.copyOf(messages);
    }
}
