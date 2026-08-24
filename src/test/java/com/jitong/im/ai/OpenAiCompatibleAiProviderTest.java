package com.jitong.im.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class OpenAiCompatibleAiProviderTest {

    @Test
    void rejects_extraction_json_that_omits_required_nullable_schema_properties() {
        UUID messageId = UUID.randomUUID();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("""
                        {
                          "actionItems": [{
                            "title": "Send proposal",
                            "details": "Send it Friday",
                            "priority": "HIGH",
                            "confidence": 0.9,
                            "sourceMessageIds": ["%s"]
                          }],
                          "keyFacts": []
                        }
                        """.formatted(messageId))))));
        OpenAiCompatibleAiProvider provider = provider(chatModel);

        assertThatThrownBy(() -> provider.extractInformation(new AiSummaryContext(
                UUID.randomUUID(),
                List.of(new AiContextMessage(messageId, 1, UUID.randomUUID(), "hello")))))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void uses_spring_ai_chat_model_and_validates_structured_summary_evidence() {
        UUID messageId = UUID.randomUUID();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("""
                        {
                          "overview": "A concise overview",
                          "keyPoints": ["One point"],
                          "decisions": [],
                          "openQuestions": [],
                          "sourceMessageIds": ["%s"]
                        }
                        """.formatted(messageId)))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(37, 5))
                        .build()));

        OpenAiCompatibleAiProvider provider = provider(chatModel);

        AiProviderResult<AiSummary> result = provider.summarize(new AiSummaryContext(
                UUID.randomUUID(),
                List.of(new AiContextMessage(messageId, 1, UUID.randomUUID(), "hello"))));

        assertThat(result.result().overview()).isEqualTo("A concise overview");
        assertThat(result.result().sourceMessageIds()).containsExactly(messageId);
        assertThat(result.inputTokens()).isEqualTo(37);
        assertThat(result.outputTokens()).isEqualTo(5);
        assertThat(result.usageReported()).isTrue();
    }

    @Test
    void marks_missing_provider_usage_for_conservative_budget_settlement() {
        UUID messageId = UUID.randomUUID();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("""
                        {
                          "overview": "A concise overview",
                          "keyPoints": [],
                          "decisions": [],
                          "openQuestions": [],
                          "sourceMessageIds": ["%s"]
                        }
                        """.formatted(messageId))))));
        OpenAiCompatibleAiProvider provider = provider(chatModel);

        AiProviderResult<AiSummary> result = provider.summarize(new AiSummaryContext(
                UUID.randomUUID(),
                List.of(new AiContextMessage(messageId, 1, UUID.randomUUID(), "hello"))));

        assertThat(result.usageReported()).isFalse();
        assertThat(result.totalTokens()).isZero();
    }

    @Test
    void sends_normalized_image_bytes_as_media_without_exposing_object_addresses() {
        UUID messageId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        byte[] imageBytes = new byte[]{1, 2, 3, 4};
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("""
                        {
                          "overview": "An image summary",
                          "keyPoints": [],
                          "decisions": [],
                          "openQuestions": [],
                          "sourceMessageIds": ["%s"]
                        }
                        """.formatted(messageId))))));
        OpenAiCompatibleAiProvider provider = provider(chatModel, true);

        provider.summarize(new AiSummaryContext(
                UUID.randomUUID(),
                List.of(new AiContextMessage(
                        messageId,
                        1,
                        UUID.randomUUID(),
                        "IMAGE",
                        "[图片]",
                        mediaId,
                        "a".repeat(64))),
                List.of(new AiContextImage(
                        messageId,
                        mediaId,
                        "image/jpeg",
                        4,
                        2,
                        imageBytes))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        UserMessage userMessage = prompt.getValue().getUserMessage();
        assertThat(userMessage.getMedia()).singleElement().satisfies(media ->
                assertThat(media.getDataAsByteArray()).containsExactly(imageBytes));
        assertThat(userMessage.getText())
                .contains("text=[图片]", "imageInput=1")
                .doesNotContain("message-images/", "http://", mediaId.toString());
        assertThat(provider.supportsVision()).isTrue();
    }

    private OpenAiCompatibleAiProvider provider(ChatModel chatModel) {
        return provider(chatModel, false);
    }

    private OpenAiCompatibleAiProvider provider(ChatModel chatModel, boolean supportsVision) {
        AiProperties properties = new AiProperties(
                "summary-v1",
                new AiProperties.Provider(
                        true,
                        "http://provider.test/v1",
                        "secret",
                        "test-model",
                        supportsVision),
                new AiProperties.Worker(250),
                null);
        return new OpenAiCompatibleAiProvider(
                properties,
                new ObjectMapper().findAndRegisterModules(),
                chatModel);
    }
}
