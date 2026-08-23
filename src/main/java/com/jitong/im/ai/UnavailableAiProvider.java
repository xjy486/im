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
    public AiSummary summarize(AiSummaryContext context) {
        throw new AiProviderException(
                "AI_PROVIDER_UNAVAILABLE",
                "No AI provider is configured");
    }

    @Override
    public String model() {
        return "unconfigured";
    }
}
