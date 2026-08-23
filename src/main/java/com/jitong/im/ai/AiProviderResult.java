package com.jitong.im.ai;

public record AiProviderResult<T>(
        T result,
        int inputTokens,
        int outputTokens,
        boolean usageReported
) {
    public AiProviderResult(T result, int inputTokens, int outputTokens) {
        this(result, inputTokens, outputTokens, true);
    }

    public AiProviderResult {
        if (result == null || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("AI provider result and token usage must be valid");
        }
    }

    public int totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }
}
