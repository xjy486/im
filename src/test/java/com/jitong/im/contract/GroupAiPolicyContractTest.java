package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.ai.AiContextMessage;
import com.jitong.im.ai.AiProvider;
import com.jitong.im.ai.AiProviderResult;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "jitong.ai.provider.model=group-policy-model",
        "jitong.ai.worker.poll-interval=50",
        "jitong.ai.budget.daily-token-limit=100000",
        "jitong.ai.budget.max-output-tokens=512"
})
class GroupAiPolicyContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiProvider provider;

    @Test
    void owner_enables_group_ai_and_members_observe_the_cloud_policy_and_system_event() {
        GroupFixture group = createGroupWithMember("Visible policy");
        UUID conversationId = group.conversationId();

        ResponseEntity<JsonNode> legacyConsent = exchange(
                "/api/v1/conversations/" + conversationId + "/ai/consent",
                HttpMethod.PATCH,
                group.memberToken(),
                Map.of("enabled", true));
        assertThat(legacyConsent.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(legacyConsent.getBody().get("code").asText()).isEqualTo("NOT_CONTACT");

        JsonNode initial = exchange(
                "/api/v1/groups/" + conversationId + "/ai-policy",
                HttpMethod.GET,
                group.memberToken(),
                null).getBody();
        assertThat(initial.get("enabled").asBoolean()).isFalse();
        assertThat(initial.get("policyVersion").asLong()).isEqualTo(1);

        ResponseEntity<JsonNode> forbidden = exchange(
                "/api/v1/groups/" + conversationId + "/ai-policy",
                HttpMethod.PATCH,
                group.memberToken(),
                Map.of("enabled", true));
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody().get("code").asText()).isEqualTo("FORBIDDEN_ROLE");

        JsonNode enabled = exchange(
                "/api/v1/groups/" + conversationId + "/ai-policy",
                HttpMethod.PATCH,
                group.ownerToken(),
                Map.of("enabled", true)).getBody();
        assertThat(enabled.get("enabled").asBoolean()).isTrue();
        assertThat(enabled.get("policyVersion").asLong()).isEqualTo(2);

        JsonNode memberGroups = exchange(
                "/api/v1/groups",
                HttpMethod.GET,
                group.memberToken(),
                null).getBody();
        assertThat(memberGroups.get(0).get("aiEnabled").asBoolean()).isTrue();
        assertThat(memberGroups.get(0).get("aiPolicyVersion").asLong()).isEqualTo(2);

        JsonNode history = exchange(
                "/api/v1/conversations/" + conversationId + "/messages?afterSeq=0&limit=200",
                HttpMethod.GET,
                group.memberToken(),
                null).getBody();
        assertThat(history.get("messages"))
                .extracting(message -> message.get("systemEventType").asText())
                .contains("AI_POLICY_CHANGED");

        sendText(group.memberToken(), conversationId, "Summarize the enabled group context.");
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            return result(new AiSummary(
                    "Group summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });
        JsonNode queued = requestSummary(group.memberToken(), conversationId).getBody();
        awaitStatus(group.memberToken(), UUID.fromString(queued.get("jobId").asText()), "SUCCEEDED");
    }

    @Test
    void disabling_group_ai_rejects_new_work_and_cancels_unfinished_jobs_without_results()
            throws Exception {
        GroupFixture group = createGroupWithMember("Disable policy");
        UUID conversationId = group.conversationId();
        updatePolicy(group.ownerToken(), conversationId, true);
        sendText(group.ownerToken(), conversationId, "Do not save a late owner result.");

        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        blockProvider(providerStarted, releaseProvider, "Late owner result.");

        UUID runningJobId = UUID.fromString(requestSummary(group.ownerToken(), conversationId)
                .getBody().get("jobId").asText());
        assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        JsonNode unchanged = updatePolicy(group.ownerToken(), conversationId, true);
        assertThat(unchanged.get("enabled").asBoolean()).isTrue();
        assertThat(unchanged.get("policyVersion").asLong()).isEqualTo(2);
        JsonNode runningJob = exchange(
                "/api/v1/ai/jobs/" + runningJobId,
                HttpMethod.GET,
                group.ownerToken(),
                null).getBody();
        assertThat(runningJob.get("status").asText()).isEqualTo("RUNNING");
        assertThat(runningJob.get("errorCode").isNull()).isTrue();

        UUID queuedJobId = UUID.fromString(requestSummary(group.memberToken(), conversationId)
                .getBody().get("jobId").asText());

        try {
            JsonNode disabled = updatePolicy(group.ownerToken(), conversationId, false);
            assertThat(disabled.get("enabled").asBoolean()).isFalse();
            assertThat(disabled.get("policyVersion").asLong()).isEqualTo(3);

            assertContextChangedJob(group.ownerToken(), runningJobId, "CANCELLED");
            assertContextChangedJob(group.memberToken(), queuedJobId, "CANCELLED");

            ResponseEntity<JsonNode> rejected = requestSummary(group.memberToken(), conversationId);
            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(rejected.getBody().get("code").asText()).isEqualTo("AI_CONSENT_REQUIRED");
        } finally {
            releaseProvider.countDown();
        }

        assertJobRemainsWithoutResult(group.ownerToken(), runningJobId, "CANCELLED");
        assertThat(exchange("/api/v1/ai/artifacts", HttpMethod.GET, group.ownerToken(), null).getBody())
                .isEmpty();
    }

    @Test
    void ownership_transfer_disables_ai_increments_the_policy_and_requires_the_new_owner_to_enable_it()
            throws Exception {
        GroupFixture group = createGroupWithMember("Ownership transfer");
        UUID conversationId = group.conversationId();
        updatePolicy(group.ownerToken(), conversationId, true);
        sendText(group.memberToken(), conversationId, "The new owner must consent again.");

        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        blockProvider(providerStarted, releaseProvider, "Stale ownership result.");
        UUID jobId = UUID.fromString(requestSummary(group.memberToken(), conversationId)
                .getBody().get("jobId").asText());
        assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            ResponseEntity<JsonNode> transferred = exchange(
                    "/api/v1/groups/" + conversationId + "/owner-transfer",
                    HttpMethod.POST,
                    group.ownerToken(),
                    Map.of("userId", group.member().userId()));
            assertThat(transferred.getStatusCode()).isEqualTo(HttpStatus.OK);

            JsonNode disabled = exchange(
                    "/api/v1/groups/" + conversationId + "/ai-policy",
                    HttpMethod.GET,
                    group.memberToken(),
                    null).getBody();
            assertThat(disabled.get("enabled").asBoolean()).isFalse();
            assertThat(disabled.get("policyVersion").asLong()).isEqualTo(3);
            assertContextChangedJob(group.memberToken(), jobId, "CANCELLED");

            ResponseEntity<JsonNode> oldOwnerCannotEnable = exchange(
                    "/api/v1/groups/" + conversationId + "/ai-policy",
                    HttpMethod.PATCH,
                    group.ownerToken(),
                    Map.of("enabled", true));
            assertThat(oldOwnerCannotEnable.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(oldOwnerCannotEnable.getBody().get("code").asText())
                    .isEqualTo("FORBIDDEN_ROLE");

            JsonNode reenabled = updatePolicy(group.memberToken(), conversationId, true);
            assertThat(reenabled.get("enabled").asBoolean()).isTrue();
            assertThat(reenabled.get("policyVersion").asLong()).isEqualTo(4);
        } finally {
            releaseProvider.countDown();
        }
    }

    @Test
    void leaving_and_removed_members_finish_their_unfinished_group_ai_jobs_as_context_changed()
            throws Exception {
        GroupFixture group = createGroupWithMember("Membership invalidation");
        TestUser removed = createUser("Removed AI member");
        String removedToken = login(removed.accountNo(), "removed-ai-member");
        addMember(group.ownerToken(), removedToken, group.conversationId(), removed.accountNo());
        updatePolicy(group.ownerToken(), group.conversationId(), true);
        sendText(group.ownerToken(), group.conversationId(), "Invalidate jobs when membership ends.");

        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        blockProvider(providerStarted, releaseProvider, "Stale membership result.");
        UUID leavingJobId = UUID.fromString(requestSummary(group.memberToken(), group.conversationId())
                .getBody().get("jobId").asText());
        assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        UUID removedJobId = UUID.fromString(requestSummary(removedToken, group.conversationId())
                .getBody().get("jobId").asText());

        try {
            assertThat(exchange(
                    "/api/v1/groups/" + group.conversationId() + "/leave",
                    HttpMethod.POST,
                    group.memberToken(),
                    null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(exchange(
                    "/api/v1/groups/" + group.conversationId() + "/members/" + removed.userId(),
                    HttpMethod.DELETE,
                    group.ownerToken(),
                    null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            assertContextChangedJob(group.memberToken(), leavingJobId, "FAILED");
            assertContextChangedJob(removedToken, removedJobId, "FAILED");
        } finally {
            releaseProvider.countDown();
        }

        assertJobRemainsWithoutResult(group.memberToken(), leavingJobId, "FAILED");
        assertJobRemainsWithoutResult(removedToken, removedJobId, "FAILED");
    }

    @Test
    void newly_added_members_cannot_use_group_ai_to_read_messages_before_their_history_boundary() {
        TestUser owner = createUser("History boundary owner");
        TestUser member = createUser("History boundary member");
        String ownerToken = login(owner.accountNo(), "history-boundary-owner");
        String memberToken = login(member.accountNo(), "history-boundary-member");
        UUID conversationId = UUID.fromString(exchange(
                "/api/v1/groups",
                HttpMethod.POST,
                ownerToken,
                Map.of(
                        "name", "History boundary",
                        "description", "",
                        "visibility", "PRIVATE"))
                .getBody().get("conversationId").asText());
        updatePolicy(ownerToken, conversationId, true);
        sendText(ownerToken, conversationId, "This message predates the new member.");
        addMember(ownerToken, memberToken, conversationId, member.accountNo());

        ResponseEntity<JsonNode> response = requestSummary(memberToken, conversationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    private GroupFixture createGroupWithMember(String name) {
        TestUser owner = createUser(name + " owner");
        TestUser member = createUser(name + " member");
        String ownerToken = login(owner.accountNo(), name + "-owner");
        String memberToken = login(member.accountNo(), name + "-member");
        JsonNode created = exchange(
                "/api/v1/groups",
                HttpMethod.POST,
                ownerToken,
                Map.of("name", name, "description", "", "visibility", "PRIVATE"))
                .getBody();
        assertThat(created.get("aiEnabled").asBoolean()).isFalse();
        assertThat(created.get("aiPolicyVersion").asLong()).isEqualTo(1);
        UUID conversationId = UUID.fromString(created.get("conversationId").asText());
        addMember(ownerToken, memberToken, conversationId, member.accountNo());
        return new GroupFixture(owner, member, ownerToken, memberToken, conversationId);
    }

    private void addMember(
            String ownerToken,
            String memberToken,
            UUID conversationId,
            String accountNo
    ) {
        JsonNode invitation = exchange(
                "/api/v1/groups/" + conversationId + "/member-invitations",
                HttpMethod.POST,
                ownerToken,
                Map.of("accountNo", accountNo)).getBody();
        assertThat(exchange(
                "/api/v1/groups/" + conversationId
                        + "/member-invitations/"
                        + invitation.get("invitationId").asText()
                        + "/accept",
                HttpMethod.POST,
                memberToken,
                null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode updatePolicy(String ownerToken, UUID conversationId, boolean enabled) {
        ResponseEntity<JsonNode> response = exchange(
                "/api/v1/groups/" + conversationId + "/ai-policy",
                HttpMethod.PATCH,
                ownerToken,
                Map.of("enabled", enabled));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode sendText(String token, UUID conversationId, String text) {
        return exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                token,
                Map.of("clientMsgId", UUID.randomUUID(), "text", text)).getBody();
    }

    private ResponseEntity<JsonNode> requestSummary(String token, UUID conversationId) {
        return exchange(
                "/api/v1/conversations/" + conversationId + "/ai/summary",
                HttpMethod.POST,
                token,
                Map.of("requestId", UUID.randomUUID(), "afterSeq", 0));
    }

    private void blockProvider(
            CountDownLatch providerStarted,
            CountDownLatch releaseProvider,
            String overview
    ) {
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            providerStarted.countDown();
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("provider was not released");
            }
            return result(new AiSummary(
                    overview,
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });
    }

    private void assertContextChangedJob(String token, UUID jobId, String status) {
        JsonNode job = exchange("/api/v1/ai/jobs/" + jobId, HttpMethod.GET, token, null).getBody();
        assertThat(job.get("status").asText()).isEqualTo(status);
        assertThat(job.get("errorCode").asText()).isEqualTo("CONTEXT_CHANGED");
        assertThat(job.get("result").isNull()).isTrue();
    }

    private void assertJobRemainsWithoutResult(String token, UUID jobId, String status) {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertContextChangedJob(token, jobId, status));
    }

    private void awaitStatus(String token, UUID jobId, String status) {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(exchange(
                        "/api/v1/ai/jobs/" + jobId,
                        HttpMethod.GET,
                        token,
                        null).getBody().get("status").asText()).isEqualTo(status));
    }

    private TestUser createUser(String displayName) {
        HttpHeaders headers = jsonHeaders();
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        JsonNode response = http.exchange(
                "/api/v1/admin/users",
                HttpMethod.POST,
                new HttpEntity<>(write(Map.of(
                        "displayName", displayName,
                        "password", "correct horse battery staple")), headers),
                JsonNode.class).getBody();
        return new TestUser(
                UUID.fromString(response.get("userId").asText()),
                response.get("accountNo").asText());
    }

    private String login(String accountNo, String installationId) {
        return exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                null,
                Map.of(
                        "accountNo", accountNo,
                        "password", "correct horse battery staple",
                        "deviceClass", "PC",
                        "installationId", installationId))
                .getBody().get("accessToken").asText();
    }

    private ResponseEntity<JsonNode> exchange(
            String path,
            HttpMethod method,
            String token,
            Object body
    ) {
        HttpHeaders headers = jsonHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
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

    private AiProviderResult<AiSummary> result(AiSummary summary) {
        return new AiProviderResult<>(summary, 10, 5, true);
    }

    private record TestUser(UUID userId, String accountNo) {
    }

    private record GroupFixture(
            TestUser owner,
            TestUser member,
            String ownerToken,
            String memberToken,
            UUID conversationId
    ) {
    }
}
