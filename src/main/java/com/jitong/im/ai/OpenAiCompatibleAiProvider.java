package com.jitong.im.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
class OpenAiCompatibleAiProvider implements AiProvider {

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    OpenAiCompatibleAiProvider(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public AiSummary summarize(AiSummaryContext context) {
        String baseUrl = properties.provider().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AiProviderException("AI_PROVIDER_UNAVAILABLE", "No AI provider is configured");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model());
        request.put("temperature", 0);
        request.put("messages", List.of(
                Map.of(
                        "role", "system",
                        "content", "Return only a JSON object with overview, keyPoints, decisions, openQuestions and sourceMessageIds. Do not invent message IDs."),
                Map.of("role", "user", "content", prompt(context))));
        request.put("response_format", Map.of("type", "json_object"));

        try {
            JsonNode response = restClient.post()
                    .uri(normalizeCompletionsUrl(baseUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (properties.provider().apiKey() != null
                                && !properties.provider().apiKey().isBlank()) {
                            headers.setBearerAuth(properties.provider().apiKey());
                        }
                    })
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            return parseResponse(response, context);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiProviderException("AI_PROVIDER_FAILURE", "The AI provider request failed", exception);
        }
    }

    @Override
    public String model() {
        return properties.provider().model();
    }

    private AiSummary parseResponse(JsonNode response, AiSummaryContext context) {
        if (response == null || !response.path("choices").isArray()
                || response.path("choices").isEmpty()) {
            throw new AiProviderException("AI_INVALID_RESULT", "The AI provider returned no choices");
        }
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new AiProviderException("AI_INVALID_RESULT", "The AI provider returned no JSON content");
        }
        try {
            JsonNode result = objectMapper.readTree(content.asText());
            AiSummary summary = new AiSummary(
                    requiredText(result, "overview"),
                    textList(result, "keyPoints"),
                    textList(result, "decisions"),
                    textList(result, "openQuestions"),
                    uuidList(result, "sourceMessageIds"));
            AiSummaryValidator.validate(summary, context);
            return summary;
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("AI_INVALID_RESULT", "The AI provider returned invalid JSON", exception);
        }
    }

    private String prompt(AiSummaryContext context) {
        StringBuilder prompt = new StringBuilder("Summarize this ordered C2C message context.\n");
        for (AiContextMessage message : context.messages()) {
            prompt.append("messageId=").append(message.messageId())
                    .append(" seq=").append(message.conversationSeq())
                    .append(" sender=").append(message.senderId())
                    .append(" text=").append(message.text())
                    .append('\n');
        }
        return prompt.toString();
    }

    private String normalizeCompletionsUrl(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized.endsWith("/chat/completions") ? normalized : normalized + "/chat/completions";
    }

    private String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new AiProviderException("AI_INVALID_RESULT", "Missing summary field: " + field);
        }
        return value.asText();
    }

    private List<String> textList(JsonNode parent, String field) {
        JsonNode values = parent.get(field);
        if (values == null || !values.isArray()) {
            throw new AiProviderException("AI_INVALID_RESULT", "Missing summary array: " + field);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new AiProviderException("AI_INVALID_RESULT", "Invalid summary item: " + field);
            }
            result.add(value.asText());
        }
        return result;
    }

    private List<UUID> uuidList(JsonNode parent, String field) {
        JsonNode values = parent.get(field);
        if (values == null || !values.isArray()) {
            throw new AiProviderException("AI_INVALID_RESULT", "Missing evidence array: " + field);
        }
        List<UUID> result = new ArrayList<>();
        for (JsonNode value : values) {
            try {
                result.add(UUID.fromString(value.asText()));
            } catch (RuntimeException exception) {
                throw new AiProviderException("AI_INVALID_RESULT", "Invalid evidence message ID", exception);
            }
        }
        return result;
    }
}
