package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.ai.AiContextMessage;
import com.jitong.im.ai.AiProvider;
import com.jitong.im.ai.AiSummary;
import com.jitong.im.ai.AiSummaryContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "jitong.ai.provider.model=contract-summary-model",
        "jitong.ai.worker.poll-interval=50"
})
class AiSummaryContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiProvider provider;

    @Test
    void requires_bilateral_consent_queues_a_private_summary_and_delivers_a_structured_result() {
        TestUser alice = createUser("AI Alice");
        TestUser bob = createUser("AI Bob");
        String aliceToken = login(alice.accountNo(), "ai-alice-installation", "PC");
        String bobToken = login(bob.accountNo(), "ai-bob-installation", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);

        JsonNode firstMessage = post(
                "/api/v1/conversations/" + conversationId + "/messages",
                aliceToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Let's ship the summary flow."));
        UUID firstMessageId = UUID.fromString(firstMessage.get("messageId").asText());
        JsonNode recalledMessage = post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "This content will be recalled."));
        UUID recalledMessageId = UUID.fromString(recalledMessage.get("messageId").asText());
        assertThat(exchange(
                "/api/v1/messages/" + recalledMessageId + "/recall",
                HttpMethod.POST,
                bobToken,
                null).getStatusCode()).isEqualTo(HttpStatus.OK);

        when(provider.model()).thenReturn("contract-summary-model");
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            assertThat(context.messages())
                    .extracting(AiContextMessage::messageId)
                    .containsExactly(firstMessageId)
                    .doesNotContain(recalledMessageId);
            return new AiSummary(
                    "The participants agreed on the next step.",
                    List.of("Continue the implementation."),
                    List.of("Use the agreed API."),
                    List.of(),
                    context.messages().stream().map(message -> message.messageId()).toList());
        });

        assertThat(exchange(
                "/api/v1/conversations/" + conversationId + "/ai/consent",
                HttpMethod.PATCH,
                aliceToken,
                Map.of("enabled", true)).getBody().get("enabledForBoth").asBoolean())
                .isFalse();
        assertThat(exchange(
                "/api/v1/conversations/" + conversationId + "/ai/summary",
                HttpMethod.POST,
                aliceToken,
                Map.of("requestId", UUID.randomUUID(), "afterSeq", 0, "untilSeq", 1))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(exchange(
                "/api/v1/conversations/" + conversationId + "/ai/consent",
                HttpMethod.PATCH,
                bobToken,
                Map.of("enabled", true)).getBody().get("enabledForBoth").asBoolean())
                .isTrue();

        UUID requestId = UUID.randomUUID();
        JsonNode queued = exchange(
                "/api/v1/conversations/" + conversationId + "/ai/summary",
                HttpMethod.POST,
                aliceToken,
                Map.of("requestId", requestId, "afterSeq", 0, "untilSeq", 2))
                .getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        assertThat(queued.get("status").asText()).isEqualTo("QUEUED");

        awaitJob(aliceToken, jobId);

        JsonNode completed = exchange(
                "/api/v1/ai/jobs/" + jobId,
                HttpMethod.GET,
                aliceToken,
                null).getBody();
        assertThat(completed.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(completed.get("result").get("sourceMessageIds").get(0).asText())
                .isEqualTo(firstMessageId.toString());

        JsonNode ownerSync = exchange(
                "/api/v1/sync?after=0&limit=200",
                HttpMethod.GET,
                aliceToken,
                null).getBody();
        List<String> ownerEvents = new java.util.ArrayList<>();
        ownerSync.get("events").forEach(event -> ownerEvents.add(event.get("eventType").asText()));
        assertThat(ownerEvents).contains("AI_JOB_QUEUED", "AI_JOB_STARTED", "AI_JOB_COMPLETED");

        JsonNode otherUser = exchange(
                "/api/v1/ai/jobs/" + jobId,
                HttpMethod.GET,
                bobToken,
                null).getBody();
        assertThat(otherUser.get("code").asText()).isEqualTo("AI_NOT_FOUND");
    }

    private void awaitJob(String token, UUID jobId) {
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(exchange(
                        "/api/v1/ai/jobs/" + jobId,
                        HttpMethod.GET,
                        token,
                        null).getBody().get("status").asText()).isEqualTo("SUCCEEDED"));
    }

    private TestUser createUser(String displayName) {
        HttpHeaders headers = jsonHeaders();
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        ResponseEntity<JsonNode> response = http.exchange(
                "/api/v1/admin/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        write(Map.of("displayName", displayName, "password", "correct horse battery staple")),
                        headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new TestUser(
                UUID.fromString(response.getBody().get("userId").asText()),
                response.getBody().get("accountNo").asText());
    }

    private String login(String accountNo, String installationId, String deviceClass) {
        ResponseEntity<JsonNode> response = http.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        write(Map.of(
                                "accountNo", accountNo,
                                "password", "correct horse battery staple",
                                "deviceClass", deviceClass,
                                "installationId", installationId)),
                        jsonHeaders()),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("accessToken").asText();
    }

    private UUID acceptContact(String aliceToken, TestUser bob, String bobToken) {
        JsonNode request = post(
                "/api/v1/contact-requests",
                aliceToken,
                Map.of("accountNo", bob.accountNo(), "verification", ""));
        UUID requestId = UUID.fromString(request.get("requestId").asText());
        return UUID.fromString(exchange(
                "/api/v1/contact-requests/" + requestId + "/accept",
                HttpMethod.POST,
                bobToken,
                null).getBody().get("conversationId").asText());
    }

    private JsonNode post(String path, String token, Object body) {
        return exchange(path, HttpMethod.POST, token, body).getBody();
    }

    private ResponseEntity<JsonNode> exchange(
            String path,
            HttpMethod method,
            String token,
            Object body
    ) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        return http.exchange(
                path,
                method,
                new HttpEntity<>(body == null ? null : write(body), headers),
                JsonNode.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record TestUser(UUID userId, String accountNo) {
    }
}
