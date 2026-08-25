package com.jitong.im.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiConfigurationTest {

    @Test
    void application_configuration_binds_ai_provider_properties_from_environment_placeholders() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(AiPropertiesConfiguration.class)
                .withPropertyValues(
                        "spring.config.location=classpath:/application.yml",
                        "AI_PROVIDER_ENABLED=true",
                        "AI_PROVIDER_BASE_URL=https://provider.test/v1",
                        "AI_PROVIDER_API_KEY=test-key",
                        "AI_PROVIDER_MODEL=test-model")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AiProperties.Provider provider = context.getBean(AiProperties.class).provider();
                    assertThat(provider.enabled()).isTrue();
                    assertThat(provider.baseUrl()).isEqualTo("https://provider.test/v1");
                    assertThat(provider.apiKey()).isEqualTo("test-key");
                    assertThat(provider.model()).isEqualTo("test-model");
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429})
    void configured_open_ai_api_preserves_retryable_http_failures(int responseStatus) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "rate limited".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiProperties properties = new AiProperties(
                    "summary-v1",
                    new AiProperties.Provider(
                            true,
                            "http://localhost:" + server.getAddress().getPort() + "/v1",
                            "secret",
                            "test-model",
                            Duration.ofSeconds(2)),
                    new AiProperties.Worker(250),
                    null);
            OpenAiApi api = new AiConfiguration().aiOpenAiApi(properties);
            OpenAiApi.ChatCompletionRequest request = new OpenAiApi.ChatCompletionRequest(
                    List.of(new OpenAiApi.ChatCompletionMessage(
                            "hello",
                            OpenAiApi.ChatCompletionMessage.Role.USER)),
                    "test-model",
                    0.0);

            assertThatThrownBy(() -> api.chatCompletionEntity(request))
                    .isInstanceOf(TransientAiException.class);
        } finally {
            server.stop(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class AiPropertiesConfiguration {
    }
}
