package com.jitong.im.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleAiProviderTest {

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
                        """.formatted(messageId))))));

        AiProperties properties = new AiProperties(
                "summary-v1",
                new AiProperties.Provider(true, "http://provider.test/v1", "secret", "test-model"),
                new AiProperties.Worker(250));
        OpenAiCompatibleAiProvider provider = new OpenAiCompatibleAiProvider(
                properties,
                new ObjectMapper().findAndRegisterModules(),
                chatModel);

        AiSummary result = provider.summarize(new AiSummaryContext(
                UUID.randomUUID(),
                List.of(new AiContextMessage(messageId, 1, UUID.randomUUID(), "hello"))));

        assertThat(result.overview()).isEqualTo("A concise overview");
        assertThat(result.sourceMessageIds()).containsExactly(messageId);
    }
}
