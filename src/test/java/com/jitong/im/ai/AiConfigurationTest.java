package com.jitong.im.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiConfigurationTest {

    @Test
    void configured_open_ai_api_preserves_429_as_a_retryable_failure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "rate limited".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
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
}
