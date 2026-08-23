package com.jitong.im.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "jitong.ai.provider",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
class UnavailableAiProvider implements AiProvider {

    @Override
    public AiProviderResult<AiSummary> summarize(AiSummaryContext context) {
        throw unavailable();
    }

    @Override
    public AiProviderResult<AiSmartReplies> smartReplies(AiSummaryContext context) {
        throw unavailable();
    }

    @Override
    public AiProviderResult<AiExtraction> extractInformation(AiSummaryContext context) {
        throw unavailable();
    }

    private AiProviderException unavailable() {
        throw new AiProviderException(
                "AI_PROVIDER_UNAVAILABLE",
                "No AI provider is configured");
    }

    @Override
    public String model() {
        return "unconfigured";
    }
}
