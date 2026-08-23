package com.jitong.im.ai;

import java.util.List;
import java.util.UUID;

public record AiSummary(
        String overview,
        List<String> keyPoints,
        List<String> decisions,
        List<String> openQuestions,
        List<UUID> sourceMessageIds
) {
    public AiSummary {
        keyPoints = List.copyOf(keyPoints);
        decisions = List.copyOf(decisions);
        openQuestions = List.copyOf(openQuestions);
        sourceMessageIds = List.copyOf(sourceMessageIds);
    }
}
