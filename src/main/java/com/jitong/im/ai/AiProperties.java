package com.jitong.im.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jitong.ai")
public record AiProperties(
        String promptVersion,
        Provider provider,
        Worker worker,
        Budget budget
) {

    public AiProperties {
        promptVersion = promptVersion == null || promptVersion.isBlank() ? "summary-v1" : promptVersion;
        provider = provider == null ? new Provider(false, null, null, "gpt-4o-mini") : provider;
        worker = worker == null ? new Worker(250) : worker;
        budget = budget == null ? new Budget(100_000, 1_024) : budget;
    }

    public record Provider(
            boolean enabled,
            String baseUrl,
            String apiKey,
            String model
    ) {
        public Provider {
            model = model == null || model.isBlank() ? "gpt-4o-mini" : model;
        }
    }

    public record Worker(long pollInterval) {
        public Worker {
            if (pollInterval < 1) {
                pollInterval = 250;
            }
        }
    }

    public record Budget(long dailyTokenLimit, int maxOutputTokens) {
        public Budget {
            if (dailyTokenLimit < 1) {
                dailyTokenLimit = 100_000;
            }
            if (maxOutputTokens < 1) {
                maxOutputTokens = 1_024;
            }
        }
    }
}
