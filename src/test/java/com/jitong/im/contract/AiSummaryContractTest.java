package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.ai.AiContextMessage;
import com.jitong.im.ai.AiProvider;
import com.jitong.im.ai.AiProviderException;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "jitong.ai.provider.model=contract-summary-model",
        "jitong.ai.worker.poll-interval=50",
        "jitong.ai.retention-interval=50",
        "jitong.ai.retention-initial-delay=0",
        "jitong.ai.budget.daily-token-limit=100000",
        "jitong.ai.budget.max-output-tokens=512"
})
class AiSummaryContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

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
            return withUsage(new AiSummary(
                    "The participants agreed on the next step.",
                    List.of("Continue the implementation."),
                    List.of("Use the agreed API."),
                    List.of(),
                    context.messages().stream().map(message -> message.messageId()).toList()));
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

    @Test
    void concurrent_summary_requests_never_over_reserve_the_users_daily_budget() throws Exception {
        TestUser alice = createUser("Budget Alice");
        TestUser bob = createUser("Budget Bob");
        TestUser carol = createUser("Budget Carol");
        String aliceToken = login(alice.accountNo(), "budget-alice-installation", "PC");
        String bobToken = login(bob.accountNo(), "budget-bob-installation", "MOBILE");
        String carolToken = login(carol.accountNo(), "budget-carol-installation", "MOBILE");
        UUID bobConversation = acceptContact(aliceToken, bob, bobToken);
        UUID carolConversation = acceptContact(aliceToken, carol, carolToken);
        enableAi(aliceToken, bobToken, bobConversation);
        enableAi(aliceToken, carolToken, carolConversation);
        post(
                "/api/v1/conversations/" + bobConversation + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Budget snapshot one."));
        post(
                "/api/v1/conversations/" + carolConversation + "/messages",
                carolToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Budget snapshot two."));
        jdbc.sql("""
                        INSERT INTO ai_daily_budgets (
                            owner_user_id, budget_date, limit_tokens,
                            reserved_tokens, used_tokens, version
                        ) VALUES (
                            :ownerUserId,
                            (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date,
                            5000, 0, 0, 0
                        )
                        """)
                .param("ownerUserId", alice.userId())
                .update();

        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("provider was not released");
            }
            return withUsage(new AiSummary(
                    "Budgeted summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
            calls.add(() -> exchange(
                    "/api/v1/conversations/" + bobConversation + "/ai/summary",
                    HttpMethod.POST,
                    aliceToken,
                    Map.of("requestId", UUID.randomUUID(), "afterSeq", 0, "untilSeq", 1)));
            calls.add(() -> exchange(
                    "/api/v1/conversations/" + carolConversation + "/ai/summary",
                    HttpMethod.POST,
                    aliceToken,
                    Map.of("requestId", UUID.randomUUID(), "afterSeq", 0, "untilSeq", 1)));

            List<ResponseEntity<JsonNode>> responses = executor.invokeAll(calls).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertThat(responses).filteredOn(response -> response.getStatusCode().is2xxSuccessful())
                    .hasSize(1);
            assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                    .singleElement()
                    .satisfies(response -> assertThat(response.getBody().get("code").asText())
                            .isEqualTo("AI_BUDGET_EXCEEDED"));

            Map<String, Long> budget = jdbc.sql("""
                            SELECT limit_tokens, reserved_tokens, used_tokens
                            FROM ai_daily_budgets
                            WHERE owner_user_id = :ownerUserId
                            """)
                    .param("ownerUserId", alice.userId())
                    .query((row, rowNumber) -> Map.of(
                            "limit", row.getLong("limit_tokens"),
                            "reserved", row.getLong("reserved_tokens"),
                            "used", row.getLong("used_tokens")))
                    .single();
            assertThat(budget.get("reserved") + budget.get("used"))
                    .isLessThanOrEqualTo(budget.get("limit"));
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void summary_queue_is_bounded_to_one_running_and_three_waiting_jobs_per_user() throws Exception {
        TestUser alice = createUser("Queue Alice");
        TestUser bob = createUser("Queue Bob");
        String aliceToken = login(alice.accountNo(), "queue-alice-installation", "PC");
        String bobToken = login(bob.accountNo(), "queue-bob-installation", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Bound this summary queue."));

        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            providerStarted.countDown();
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("provider was not released");
            }
            return withUsage(new AiSummary(
                    "Queued summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });

        try {
            ResponseEntity<JsonNode> running = requestSummary(aliceToken, conversationId);
            assertThat(running.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            for (int queued = 0; queued < 3; queued++) {
                assertThat(requestSummary(aliceToken, conversationId).getStatusCode())
                        .isEqualTo(HttpStatus.OK);
            }

            ResponseEntity<JsonNode> rejected = requestSummary(aliceToken, conversationId);
            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(rejected.getBody().get("code").asText()).isEqualTo("AI_BUSY");
            assertThat(jdbc.sql("""
                            SELECT COUNT(*)
                            FROM ai_jobs
                            WHERE owner_user_id = :ownerUserId AND status = 'RUNNING'
                            """)
                    .param("ownerUserId", alice.userId())
                    .query(Long.class)
                    .single()).isEqualTo(1);
            assertThat(jdbc.sql("""
                            SELECT COUNT(*)
                            FROM ai_jobs
                            WHERE owner_user_id = :ownerUserId AND status = 'QUEUED'
                            """)
                    .param("ownerUserId", alice.userId())
                    .query(Long.class)
                    .single()).isEqualTo(3);
        } finally {
            releaseProvider.countDown();
        }
    }

    @Test
    void cache_hits_are_bound_to_the_requesting_user_and_identical_content_snapshot() {
        TestUser alice = createUser("Cache Alice");
        TestUser bob = createUser("Cache Bob");
        String aliceToken = login(alice.accountNo(), "cache-alice-installation", "PC");
        String bobToken = login(bob.accountNo(), "cache-bob-installation", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        JsonNode message = post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Cache this exact snapshot."));
        UUID messageId = UUID.fromString(message.get("messageId").asText());

        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            return withUsage(new AiSummary(
                    "Content-bound summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });

        JsonNode first = requestSummary(aliceToken, conversationId).getBody();
        awaitJob(aliceToken, UUID.fromString(first.get("jobId").asText()));

        JsonNode cached = requestSummary(aliceToken, conversationId).getBody();
        assertThat(cached.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(cached.get("result").get("sourceMessageIds").get(0).asText())
                .isEqualTo(messageId.toString());
        verify(provider, times(1)).summarize(any(AiSummaryContext.class));

        JsonNode otherOwner = requestSummary(bobToken, conversationId).getBody();
        awaitJob(bobToken, UUID.fromString(otherOwner.get("jobId").asText()));
        verify(provider, times(2)).summarize(any(AiSummaryContext.class));
    }

    @Test
    void recalling_a_message_changes_the_content_digest_and_invalidates_the_old_cache() {
        TestUser alice = createUser("Recall Cache Alice");
        TestUser bob = createUser("Recall Cache Bob");
        String aliceToken = login(alice.accountNo(), "recall-cache-alice", "PC");
        String bobToken = login(bob.accountNo(), "recall-cache-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Keep this message."));
        JsonNode recalled = post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Recall this message."));

        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            return withUsage(new AiSummary(
                    "Digest-bound summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });

        JsonNode first = requestSummary(aliceToken, conversationId, 2).getBody();
        awaitJob(aliceToken, UUID.fromString(first.get("jobId").asText()));
        assertThat(exchange(
                "/api/v1/messages/" + recalled.get("messageId").asText() + "/recall",
                HttpMethod.POST,
                bobToken,
                null).getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode afterRecall = requestSummary(aliceToken, conversationId, 2).getBody();
        awaitJob(aliceToken, UUID.fromString(afterRecall.get("jobId").asText()));
        verify(provider, times(2)).summarize(any(AiSummaryContext.class));
    }

    @Test
    void transient_provider_failure_is_retried_once_and_can_then_succeed() {
        TestUser alice = createUser("Retry Alice");
        TestUser bob = createUser("Retry Bob");
        String aliceToken = login(alice.accountNo(), "retry-alice", "PC");
        String bobToken = login(bob.accountNo(), "retry-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Retry one transient failure."));

        when(provider.summarize(any(AiSummaryContext.class)))
                .thenThrow(new AiProviderException(
                        "AI_PROVIDER_RATE_LIMITED",
                        "provider returned 429",
                        new TransientAiException("429 Too Many Requests")))
                .thenAnswer(invocation -> {
                    AiSummaryContext context = invocation.getArgument(0);
                    return withUsage(new AiSummary(
                            "Retry succeeded.",
                            List.of(),
                            List.of(),
                            List.of(),
                            context.messages().stream().map(AiContextMessage::messageId).toList()));
                });

        JsonNode queued = requestSummary(aliceToken, conversationId).getBody();
        awaitJob(aliceToken, UUID.fromString(queued.get("jobId").asText()));
        verify(provider, times(2)).summarize(any(AiSummaryContext.class));
    }

    @Test
    void repeated_transient_provider_failure_stops_after_one_retry() {
        TestUser alice = createUser("Retry Limit Alice");
        TestUser bob = createUser("Retry Limit Bob");
        String aliceToken = login(alice.accountNo(), "retry-limit-alice", "PC");
        String bobToken = login(bob.accountNo(), "retry-limit-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Stop after one retry."));

        when(provider.summarize(any(AiSummaryContext.class)))
                .thenThrow(new AiProviderException(
                        "AI_PROVIDER_FAILURE",
                        "provider returned 503",
                        new TransientAiException("503 Service Unavailable")));

        JsonNode queued = requestSummary(aliceToken, conversationId).getBody();
        awaitStatus(aliceToken, UUID.fromString(queued.get("jobId").asText()), "FAILED");
        verify(provider, times(2)).summarize(any(AiSummaryContext.class));
    }

    @Test
    void non_transient_provider_failure_is_not_retried() {
        TestUser alice = createUser("No Retry Alice");
        TestUser bob = createUser("No Retry Bob");
        String aliceToken = login(alice.accountNo(), "no-retry-alice", "PC");
        String bobToken = login(bob.accountNo(), "no-retry-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Do not retry invalid output."));

        when(provider.summarize(any(AiSummaryContext.class)))
                .thenThrow(new AiProviderException("AI_INVALID_RESULT", "invalid structured output"));

        JsonNode queued = requestSummary(aliceToken, conversationId).getBody();
        awaitStatus(aliceToken, UUID.fromString(queued.get("jobId").asText()), "FAILED");
        verify(provider, times(1)).summarize(any(AiSummaryContext.class));
    }

    @Test
    void successful_job_releases_its_reservation_and_settles_actual_token_usage() {
        TestUser alice = createUser("Settlement Alice");
        TestUser bob = createUser("Settlement Bob");
        String aliceToken = login(alice.accountNo(), "settlement-alice", "PC");
        String bobToken = login(bob.accountNo(), "settlement-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Settle the actual usage."));
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            return withUsage(new AiSummary(
                    "Settled summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });

        JsonNode queued = requestSummary(aliceToken, conversationId).getBody();
        awaitJob(aliceToken, UUID.fromString(queued.get("jobId").asText()));

        Map<String, Long> budget = budgetFor(alice.userId());
        assertThat(budget.get("reserved")).isZero();
        assertThat(budget.get("used")).isEqualTo(42);
    }

    @Test
    void successful_job_without_provider_usage_conservatively_consumes_its_reservation() throws Exception {
        TestUser alice = createUser("Unknown Usage Alice");
        TestUser bob = createUser("Unknown Usage Bob");
        String aliceToken = login(alice.accountNo(), "unknown-usage-alice", "PC");
        String bobToken = login(bob.accountNo(), "unknown-usage-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Settle conservatively without usage."));
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            providerStarted.countDown();
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("provider was not released");
            }
            return new AiProviderResult(new AiSummary(
                    "Conservatively settled summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()), 0, 0, false);
        });

        JsonNode queued = requestSummary(aliceToken, conversationId).getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        long reservation;
        try {
            assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            reservation = budgetFor(alice.userId()).get("reserved");
        } finally {
            releaseProvider.countDown();
        }
        awaitJob(aliceToken, jobId);

        Map<String, Long> budget = budgetFor(alice.userId());
        assertThat(budget.get("reserved")).isZero();
        assertThat(budget.get("used")).isEqualTo(reservation);
    }

    @Test
    void failed_job_releases_its_entire_reservation_without_recording_usage() {
        TestUser alice = createUser("Failure Budget Alice");
        TestUser bob = createUser("Failure Budget Bob");
        String aliceToken = login(alice.accountNo(), "failure-budget-alice", "PC");
        String bobToken = login(bob.accountNo(), "failure-budget-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Release failed budget."));
        when(provider.summarize(any(AiSummaryContext.class)))
                .thenThrow(new AiProviderException("AI_INVALID_RESULT", "invalid structured output"));

        JsonNode queued = requestSummary(aliceToken, conversationId).getBody();
        awaitStatus(aliceToken, UUID.fromString(queued.get("jobId").asText()), "FAILED");

        Map<String, Long> budget = budgetFor(alice.userId());
        assertThat(budget.get("reserved")).isZero();
        assertThat(budget.get("used")).isZero();
        assertThat(jdbc.sql("SELECT context_json IS NULL FROM ai_jobs WHERE id = :jobId")
                .param("jobId", UUID.fromString(queued.get("jobId").asText()))
                .query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void stale_running_job_is_fenced_requeued_once_and_does_not_block_the_owner() throws Exception {
        TestUser alice = createUser("Lease Alice");
        TestUser bob = createUser("Lease Bob");
        String aliceToken = login(alice.accountNo(), "lease-alice", "PC");
        String bobToken = login(bob.accountNo(), "lease-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Recover this stale worker claim."));

        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstCallStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            if (context.conversationId().equals(conversationId) && calls.incrementAndGet() == 1) {
                firstCallStarted.countDown();
                if (!releaseFirstCall.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("stale provider call was not released");
                }
            }
            return withUsage(new AiSummary(
                    "Recovered summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });

        JsonNode queued = requestSummary(aliceToken, conversationId).getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        try {
            assertThat(firstCallStarted.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.sql("UPDATE ai_jobs SET started_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE id = :jobId")
                    .param("jobId", jobId)
                    .update();
            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(jdbc.sql("SELECT status FROM ai_jobs WHERE id = :jobId")
                            .param("jobId", jobId)
                            .query(String.class)
                            .single()).isEqualTo("QUEUED"));
        } finally {
            releaseFirstCall.countDown();
        }

        awaitJob(aliceToken, jobId);
        assertThat(jdbc.sql("SELECT attempt_count FROM ai_jobs WHERE id = :jobId")
                .param("jobId", jobId)
                .query(Integer.class)
                .single()).isEqualTo(2);
        assertThat(calls).hasValue(2);
        Map<String, Long> budget = budgetFor(alice.userId());
        assertThat(budget.get("reserved")).isZero();
        assertThat(budget.get("used")).isEqualTo(42);
    }

    @Test
    void retiring_an_account_cancels_writeback_and_erases_all_owner_linked_ai_data() throws Exception {
        TestUser alice = createUser("Retirement AI Alice");
        TestUser bob = createUser("Retirement AI Bob");
        String aliceToken = login(alice.accountNo(), "retirement-ai-alice", "PC");
        String bobToken = login(bob.accountNo(), "retirement-ai-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Erase this AI work on retirement."));

        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            providerStarted.countDown();
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("provider was not released");
            }
            return withUsage(new AiSummary(
                    "Retired summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });

        requestSummary(aliceToken, conversationId);
        try {
            assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            HttpHeaders headers = jsonHeaders();
            headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
            assertThat(http.exchange(
                    "/api/v1/admin/users/" + alice.userId() + "/retire",
                    HttpMethod.POST,
                    new HttpEntity<>(null, headers),
                    Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            assertOwnerAiDataErased(alice.userId());
        } finally {
            releaseProvider.countDown();
        }

        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(2))
                .untilAsserted(() -> assertOwnerAiDataErased(alice.userId()));
    }

    @Test
    void deleting_a_running_job_cancels_writeback_and_releases_its_reservation() throws Exception {
        TestUser alice = createUser("Cancellation Alice");
        TestUser bob = createUser("Cancellation Bob");
        String aliceToken = login(alice.accountNo(), "cancellation-alice", "PC");
        String bobToken = login(bob.accountNo(), "cancellation-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Cancel this running task."));

        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            providerStarted.countDown();
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("provider was not released");
            }
            return withUsage(new AiSummary(
                    "Cancelled summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });

        JsonNode queued = requestSummary(aliceToken, conversationId).getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        try {
            assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(exchange(
                    "/api/v1/ai/jobs/" + jobId,
                    HttpMethod.DELETE,
                    aliceToken,
                    null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            Map<String, Long> budget = budgetFor(alice.userId());
            assertThat(budget.get("reserved")).isZero();
            assertThat(budget.get("used")).isZero();
            assertThat(exchange(
                    "/api/v1/ai/jobs/" + jobId,
                    HttpMethod.GET,
                    aliceToken,
                    null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            releaseProvider.countDown();
        }
    }

    @Test
    void expired_running_job_releases_budget_clears_context_and_rejects_late_writeback() throws Exception {
        TestUser alice = createUser("Expiry Alice");
        TestUser bob = createUser("Expiry Bob");
        String aliceToken = login(alice.accountNo(), "expiry-alice", "PC");
        String bobToken = login(bob.accountNo(), "expiry-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Expire this running task."));

        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            providerStarted.countDown();
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("provider was not released");
            }
            return withUsage(new AiSummary(
                    "Too-late summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });

        JsonNode queued = requestSummary(aliceToken, conversationId).getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        try {
            assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.sql("UPDATE ai_jobs SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = :jobId")
                    .param("jobId", jobId)
                    .update();

            awaitStatus(aliceToken, jobId, "EXPIRED");
            assertThat(jdbc.sql("SELECT context_json IS NULL FROM ai_jobs WHERE id = :jobId")
                    .param("jobId", jobId)
                    .query(Boolean.class)
                    .single()).isTrue();
            Map<String, Long> budget = budgetFor(alice.userId());
            assertThat(budget.get("reserved")).isZero();
            assertThat(budget.get("used")).isZero();
        } finally {
            releaseProvider.countDown();
        }

        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    JsonNode expired = exchange(
                            "/api/v1/ai/jobs/" + jobId,
                            HttpMethod.GET,
                            aliceToken,
                            null).getBody();
                    assertThat(expired.get("status").asText()).isEqualTo("EXPIRED");
                    assertThat(expired.get("result").isNull()).isTrue();
                });
    }

    @Test
    void expired_cache_artifact_and_completed_job_content_are_physically_deleted() {
        TestUser alice = createUser("Expiry Cleanup Alice");
        TestUser bob = createUser("Expiry Cleanup Bob");
        String aliceToken = login(alice.accountNo(), "expiry-cleanup-alice", "PC");
        String bobToken = login(bob.accountNo(), "expiry-cleanup-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Delete expired private AI content."));
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            return withUsage(new AiSummary(
                    "Ephemeral summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });

        JsonNode queued = requestSummary(aliceToken, conversationId).getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        awaitJob(aliceToken, jobId);
        jdbc.sql("UPDATE ai_jobs SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = :jobId")
                .param("jobId", jobId)
                .update();
        jdbc.sql("UPDATE ai_artifacts SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE job_id = :jobId")
                .param("jobId", jobId)
                .update();
        jdbc.sql("UPDATE ai_cache_entries SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", alice.userId())
                .update();

        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_jobs WHERE id = :jobId")
                            .param("jobId", jobId)
                            .query(Long.class)
                            .single()).isZero();
                    assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_artifacts WHERE job_id = :jobId")
                            .param("jobId", jobId)
                            .query(Long.class)
                            .single()).isZero();
                    assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_cache_entries WHERE owner_user_id = :ownerUserId")
                            .param("ownerUserId", alice.userId())
                            .query(Long.class)
                            .single()).isZero();
                });
    }

    private void awaitJob(String token, UUID jobId) {
        awaitStatus(token, jobId, "SUCCEEDED");
    }

    private void assertOwnerAiDataErased(UUID ownerUserId) {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_jobs WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", ownerUserId)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_artifacts WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", ownerUserId)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_cache_entries WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", ownerUserId)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_daily_budgets WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", ownerUserId)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM conversation_ai_consents WHERE user_id = :ownerUserId")
                .param("ownerUserId", ownerUserId)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM user_sync_events
                        WHERE user_id = :ownerUserId AND event_type LIKE 'AI_%'
                        """)
                .param("ownerUserId", ownerUserId)
                .query(Long.class)
                .single()).isZero();
    }

    private void awaitStatus(String token, UUID jobId, String expectedStatus) {
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(exchange(
                        "/api/v1/ai/jobs/" + jobId,
                        HttpMethod.GET,
                        token,
                        null).getBody().get("status").asText()).isEqualTo(expectedStatus));
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

    private void enableAi(String firstToken, String secondToken, UUID conversationId) {
        exchange(
                "/api/v1/conversations/" + conversationId + "/ai/consent",
                HttpMethod.PATCH,
                firstToken,
                Map.of("enabled", true));
        exchange(
                "/api/v1/conversations/" + conversationId + "/ai/consent",
                HttpMethod.PATCH,
                secondToken,
                Map.of("enabled", true));
    }

    private ResponseEntity<JsonNode> requestSummary(String token, UUID conversationId) {
        return requestSummary(token, conversationId, 1);
    }

    private ResponseEntity<JsonNode> requestSummary(String token, UUID conversationId, long untilSeq) {
        return exchange(
                "/api/v1/conversations/" + conversationId + "/ai/summary",
                HttpMethod.POST,
                token,
                Map.of("requestId", UUID.randomUUID(), "afterSeq", 0, "untilSeq", untilSeq));
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

    private AiProviderResult withUsage(AiSummary summary) {
        return new AiProviderResult(summary, 37, 5);
    }

    private Map<String, Long> budgetFor(UUID ownerUserId) {
        return jdbc.sql("""
                        SELECT limit_tokens, reserved_tokens, used_tokens
                        FROM ai_daily_budgets
                        WHERE owner_user_id = :ownerUserId
                        """)
                .param("ownerUserId", ownerUserId)
                .query((row, rowNumber) -> Map.of(
                        "limit", row.getLong("limit_tokens"),
                        "reserved", row.getLong("reserved_tokens"),
                        "used", row.getLong("used_tokens")))
                .single();
    }

    private record TestUser(UUID userId, String accountNo) {
    }
}
