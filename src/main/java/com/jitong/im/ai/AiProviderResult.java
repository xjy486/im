package com.jitong.im.ai;

public record AiProviderResult(
        AiSummary summary,
        int inputTokens,
        int outputTokens
) {
    public AiProviderResult {
        if (summary == null || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("AI provider result and token usage must be valid");
        }
    }

    public int totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }
}
