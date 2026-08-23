package com.jitong.im.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jitong.ai")
public record AiProperties(
        String promptVersion,
        Provider provider,
        Worker worker
) {

    public AiProperties {
        promptVersion = promptVersion == null || promptVersion.isBlank() ? "summary-v1" : promptVersion;
        provider = provider == null ? new Provider(null, null, "gpt-4o-mini") : provider;
        worker = worker == null ? new Worker(250) : worker;
    }

    public record Provider(
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
}
