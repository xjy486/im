package com.jitong.im.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(
        prefix = "jitong.ai.provider",
        name = "enabled",
        havingValue = "true")
class OpenAiCompatibleAiProvider implements AiProvider {

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;
    private final BeanOutputConverter<AiSummary> outputConverter;
    private final BeanOutputConverter<AiSmartReplies> smartRepliesConverter;
    private final BeanOutputConverter<AiExtraction> extractionConverter;
    private final AiOutputSchemaValidator schemaValidator;

    @Autowired
    OpenAiCompatibleAiProvider(
            AiProperties properties,
            ObjectMapper objectMapper,
            ChatModel chatModel
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.chatClient = ChatClient.create(chatModel);
        this.outputConverter = new BeanOutputConverter<>(AiSummary.class, objectMapper);
        this.smartRepliesConverter = new BeanOutputConverter<>(AiSmartReplies.class, objectMapper);
        this.extractionConverter = new BeanOutputConverter<>(AiExtraction.class, objectMapper);
        this.schemaValidator = new AiOutputSchemaValidator();
    }

    @Override
    public AiProviderResult<AiSummary> summarize(AiSummaryContext context) {
        ProviderResponse<AiSummary> response = request(
                context,
                outputConverter,
                "Summarize this ordered C2C message context.");
        AiSummaryValidator.validate(response.value(), context);
        return providerResult(response);
    }

    @Override
    public AiProviderResult<AiSmartReplies> smartReplies(AiSummaryContext context) {
        ProviderResponse<AiSmartReplies> response = request(
                context,
                smartRepliesConverter,
                "Return exactly three distinct editable reply drafts for the requesting user.");
        AiSmartRepliesValidator.validate(response.value());
        return providerResult(response);
    }

    @Override
    public AiProviderResult<AiExtraction> extractInformation(AiSummaryContext context) {
        ProviderResponse<AiExtraction> response = request(
                context,
                extractionConverter,
                "Extract private action items and key facts from only this selected message context.");
        AiExtractionValidator.validate(response.value(), context);
        return providerResult(response);
    }

    private <T> ProviderResponse<T> request(
            AiSummaryContext context,
            BeanOutputConverter<T> converter,
            String instruction
    ) {
        if (!properties.provider().enabled()
                || properties.provider().baseUrl() == null
                || properties.provider().baseUrl().isBlank()
                || properties.provider().apiKey() == null
                || properties.provider().apiKey().isBlank()) {
            throw new AiProviderException("AI_PROVIDER_UNAVAILABLE", "No AI provider is configured");
        }

        try {
            ChatResponse response = chatClient.prompt(new Prompt(
                            List.of(
                                    new org.springframework.ai.chat.messages.SystemMessage(
                                            "Return only JSON matching this schema. Do not invent message IDs.\\n"
                                                    + converter.getFormat()),
                                    userMessage(context, instruction)),
                            OpenAiChatOptions.builder()
                                    .model(model())
                                    .temperature(0.0)
                                    .responseFormat(ResponseFormat.builder()
                                            .type(ResponseFormat.Type.JSON_OBJECT)
                                            .build())
                                    .maxTokens(properties.budget().maxOutputTokens())
                                    .build()))
                    .call()
                    .chatResponse();
            String content = response == null || response.getResult() == null
                    ? null
                    : response.getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                throw new AiProviderException("AI_INVALID_RESULT", "The AI provider returned no content");
            }
            schemaValidator.validate(content);
            T value = converter.convert(content);
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            int inputTokens = tokenCount(usage == null ? null : usage.getPromptTokens());
            int outputTokens = tokenCount(usage == null ? null : usage.getCompletionTokens());
            boolean usageReported = usage != null
                    && usage.getPromptTokens() != null
                    && usage.getCompletionTokens() != null
                    && (inputTokens > 0 || outputTokens > 0);
            return new ProviderResponse<>(
                    value,
                    inputTokens,
                    outputTokens,
                    usageReported);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiProviderException("AI_PROVIDER_FAILURE", "The AI provider request failed", exception);
        }
    }

    private <T> AiProviderResult<T> providerResult(ProviderResponse<T> response) {
        return new AiProviderResult<>(
                response.value(),
                response.inputTokens(),
                response.outputTokens(),
                response.usageReported());
    }

    private int tokenCount(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    @Override
    public String model() {
        return properties.provider().model();
    }

    @Override
    public boolean supportsVision() {
        return properties.provider().supportsVision();
    }

    private org.springframework.ai.chat.messages.UserMessage userMessage(
            AiSummaryContext context,
            String instruction
    ) {
        List<AiContextImage> images = supportsVision() ? context.images() : List.of();
        List<Media> media = images.stream()
                .map(image -> Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType(image.contentType()))
                        .data(new ByteArrayResource(image.content()))
                        .id(image.messageId().toString())
                        .name("normalized-message-image")
                        .build())
                .toList();
        return org.springframework.ai.chat.messages.UserMessage.builder()
                .text(prompt(context, instruction, images))
                .media(media)
                .build();
    }

    private String prompt(
            AiSummaryContext context,
            String instruction,
            List<AiContextImage> images
    ) {
        Map<java.util.UUID, Integer> imageIndexes = new HashMap<>();
        for (int index = 0; index < images.size(); index++) {
            imageIndexes.put(images.get(index).messageId(), index + 1);
        }
        StringBuilder prompt = new StringBuilder(instruction).append('\n');
        for (AiContextMessage message : context.messages()) {
            prompt.append("messageId=").append(message.messageId())
                    .append(" seq=").append(message.conversationSeq())
                    .append(" sender=").append(message.senderId())
                    .append(" type=").append(message.type())
                    .append(" text=").append(message.text());
            Integer imageIndex = imageIndexes.get(message.messageId());
            if (imageIndex != null) {
                prompt.append(" imageInput=").append(imageIndex);
            }
            prompt.append('\n');
        }
        return prompt.toString();
    }

    private record ProviderResponse<T>(
            T value,
            int inputTokens,
            int outputTokens,
            boolean usageReported
    ) {
    }
}
