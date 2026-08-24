package com.jitong.im.ai;

import java.util.List;
import java.util.UUID;

public record AiSummaryContext(
        UUID conversationId,
        List<AiContextMessage> messages,
        List<AiContextImage> images
) {

    public AiSummaryContext(UUID conversationId, List<AiContextMessage> messages) {
        this(conversationId, messages, List.of());
    }

    public AiSummaryContext {
        messages = List.copyOf(messages);
        images = List.copyOf(images);
    }
}
