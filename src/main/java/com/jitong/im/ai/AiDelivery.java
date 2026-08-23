package com.jitong.im.ai;

import java.util.UUID;

public record AiDelivery(
        UUID jobId,
        UUID conversationId,
        String kind,
        String status,
        String errorCode,
        Object result
) {
}
