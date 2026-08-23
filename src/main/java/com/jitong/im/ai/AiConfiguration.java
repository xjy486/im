package com.jitong.im.ai;

import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.retry.support.RetryTemplate;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    @Bean
    ToolCallingManager aiToolCallingManager() {
        return DefaultToolCallingManager.builder().build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "jitong.ai.provider",
            name = "enabled",
            havingValue = "true")
    OpenAiApi aiOpenAiApi(AiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.provider().requestTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.provider().requestTimeout());
        return OpenAiApi.builder()
                .baseUrl(normalizeBaseUrl(properties.provider().baseUrl()))
                .apiKey(properties.provider().apiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "jitong.ai.provider",
            name = "enabled",
            havingValue = "true")
    OpenAiChatModel aiChatModel(
            OpenAiApi api,
            AiProperties properties,
            ToolCallingManager toolCallingManager
    ) {
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(RetryTemplate.builder()
                        .maxAttempts(1)
                        .noBackoff()
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(properties.provider().model())
                        .temperature(0.0)
                        .build())
                .build();
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        if (normalized.endsWith("/chat/completions")) {
            return normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        if (normalized.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }
}
