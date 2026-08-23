package com.jitong.im.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
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
    public AiProviderResult summarize(AiSummaryContext context) {
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
                                                    + outputConverter.getFormat()),
                                    new org.springframework.ai.chat.messages.UserMessage(prompt(context))),
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
            AiSummary summary = outputConverter.convert(content);
            AiSummaryValidator.validate(summary, context);
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            return new AiProviderResult(
                    summary,
                    tokenCount(usage == null ? null : usage.getPromptTokens()),
                    tokenCount(usage == null ? null : usage.getCompletionTokens()));
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiProviderException("AI_PROVIDER_FAILURE", "The AI provider request failed", exception);
        }
    }

    private int tokenCount(Integer value) {
        return value == null || value < 0 ? 0 : value;
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
