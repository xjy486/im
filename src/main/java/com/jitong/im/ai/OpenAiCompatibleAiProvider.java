package com.jitong.im.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

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
    }

    @Override
    public AiSummary summarize(AiSummaryContext context) {
        if (!properties.provider().enabled()
                || properties.provider().baseUrl() == null
                || properties.provider().baseUrl().isBlank()
                || properties.provider().apiKey() == null
                || properties.provider().apiKey().isBlank()) {
            throw new AiProviderException("AI_PROVIDER_UNAVAILABLE", "No AI provider is configured");
        }

        try {
            String content = chatClient.prompt(new Prompt(
                            List.of(
                                    new org.springframework.ai.chat.messages.SystemMessage(
                                            "Return only JSON matching this schema. Do not invent message IDs.\\n"
                                                    + outputConverter.getFormat()),
                                    new org.springframework.ai.chat.messages.UserMessage(prompt(context))),
                            OpenAiChatOptions.builder()
                                    .model(model())
                                    .temperature(0.0)
                                    .responseFormat(ResponseFormat.builder()
                                            .type(ResponseFormat.Type.JSON_SCHEMA)
                                            .jsonSchema(outputConverter.getJsonSchema())
                                            .build())
                                    .build()))
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                throw new AiProviderException("AI_INVALID_RESULT", "The AI provider returned no content");
            }
            AiSummary summary = outputConverter.convert(content);
            AiSummaryValidator.validate(summary, context);
            return summary;
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
}
