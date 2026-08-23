package com.jitong.im.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("jitong.ai")
public record AiProperties(
        String promptVersion,
        Provider provider,
        Worker worker,
        Budget budget
) {

    public AiProperties {
        promptVersion = promptVersion == null || promptVersion.isBlank() ? "summary-v1" : promptVersion;
        provider = provider == null
                ? new Provider(false, null, null, "gpt-4o-mini", Duration.ofSeconds(30))
                : provider;
        worker = worker == null ? new Worker(250, Duration.ofMinutes(2)) : worker;
        if (worker.leaseTimeout().compareTo(provider.requestTimeout()) <= 0) {
            worker = new Worker(
                    worker.pollInterval(),
                    provider.requestTimeout().plusSeconds(30));
        }
        budget = budget == null ? new Budget(100_000, 1_024) : budget;
    }

    public record Provider(
            boolean enabled,
            String baseUrl,
            String apiKey,
            String model,
            Duration requestTimeout
    ) {
        public Provider(boolean enabled, String baseUrl, String apiKey, String model) {
            this(enabled, baseUrl, apiKey, model, Duration.ofSeconds(30));
        }

        public Provider {
            model = model == null || model.isBlank() ? "gpt-4o-mini" : model;
            requestTimeout = requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                    ? Duration.ofSeconds(30)
                    : requestTimeout;
        }
    }

    public record Worker(long pollInterval, Duration leaseTimeout) {
        public Worker(long pollInterval) {
            this(pollInterval, Duration.ofMinutes(2));
        }

        public Worker {
            if (pollInterval < 1) {
                pollInterval = 250;
            }
            leaseTimeout = leaseTimeout == null || leaseTimeout.isNegative() || leaseTimeout.isZero()
                    ? Duration.ofMinutes(2)
                    : leaseTimeout;
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
