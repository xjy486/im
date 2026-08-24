package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.ai.AiContextMessage;
import com.jitong.im.ai.AiExtraction;
import com.jitong.im.ai.AiProvider;
import com.jitong.im.ai.AiProviderException;
import com.jitong.im.ai.AiProviderResult;
import com.jitong.im.ai.AiSmartReplies;
import com.jitong.im.ai.AiSummary;
import com.jitong.im.ai.AiSummaryContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
        "jitong.ai.budget.max-output-tokens=512",
        "jitong.ai.image-input.enabled=true"
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

        JsonNode ownerJobs = exchange(
                "/api/v1/ai/jobs",
                HttpMethod.GET,
                aliceToken,
                null).getBody();
        assertThat(ownerJobs).singleElement().satisfies(job -> {
            assertThat(job.get("jobId").asText()).isEqualTo(jobId.toString());
            assertThat(job.get("status").asText()).isEqualTo("SUCCEEDED");
        });
        assertThat(exchange(
                "/api/v1/ai/jobs",
                HttpMethod.GET,
                bobToken,
                null).getBody()).isEmpty();

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
    void sends_at_most_four_authorized_images_normalized_to_1024_pixels() throws Exception {
        TestUser alice = createUser("Vision Alice");
        TestUser bob = createUser("Vision Bob");
        String aliceToken = login(alice.accountNo(), "vision-alice", "PC");
        String bobToken = login(bob.accountNo(), "vision-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);

        List<UUID> messageIds = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            messageIds.add(sendImage(
                    bobToken,
                    conversationId,
                    png(1600, 800, index + 1),
                    "vision-" + index + ".png").messageId());
        }
        when(provider.supportsVision()).thenReturn(true);
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            assertThat(context.messages()).extracting(AiContextMessage::messageId)
                    .containsExactlyElementsOf(messageIds);
            assertThat(context.images()).hasSize(4);
            assertThat(context.images()).allSatisfy(image -> {
                assertThat(Math.max(image.width(), image.height())).isLessThanOrEqualTo(1024);
                assertThat(image.contentType()).isEqualTo("image/jpeg");
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.content()));
                assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isLessThanOrEqualTo(1024);
                assertThat(new String(image.content(), StandardCharsets.ISO_8859_1))
                        .doesNotContain("Exif", "GPSLatitude", "GPSLongitude");
            });
            return withUsage(new AiSummary(
                    "Four authorized images were included.",
                    List.of(),
                    List.of(),
                    List.of(),
                    messageIds));
        });

        JsonNode queued = requestSummary(aliceToken, conversationId, 5).getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        awaitJob(aliceToken, jobId);

        assertThat(jdbc.sql("SELECT image_input_enabled FROM ai_jobs WHERE id = :jobId")
                .param("jobId", jobId)
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(jdbc.sql("SELECT image_input_enabled FROM ai_cache_entries WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", alice.userId())
                .query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void provider_without_vision_receives_image_placeholders_and_text_summary_still_succeeds()
            throws Exception {
        TestUser alice = createUser("Text fallback Alice");
        TestUser bob = createUser("Text fallback Bob");
        String aliceToken = login(alice.accountNo(), "fallback-alice", "PC");
        String bobToken = login(bob.accountNo(), "fallback-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        UUID textMessageId = UUID.fromString(post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Keep text summaries working."))
                .get("messageId").asText());
        SentImage image = sendImage(
                bobToken,
                conversationId,
                png(1200, 600, 7),
                "fallback.png");

        when(provider.supportsVision()).thenReturn(false);
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            assertThat(context.images()).isEmpty();
            assertThat(context.messages()).extracting(AiContextMessage::messageId)
                    .containsExactly(textMessageId, image.messageId());
            assertThat(context.messages().get(1).text()).isEqualTo("[图片]");
            return withUsage(new AiSummary(
                    "The text remains available and an image was shared.",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(textMessageId, image.messageId())));
        });

        JsonNode queued = requestSummary(aliceToken, conversationId, 2).getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        awaitJob(aliceToken, jobId);

        assertThat(jdbc.sql("SELECT image_input_enabled FROM ai_jobs WHERE id = :jobId")
                .param("jobId", jobId)
                .query(Boolean.class)
                .single()).isFalse();
    }

    @Test
    void recalled_and_expired_images_are_never_loaded_for_the_provider() throws Exception {
        TestUser alice = createUser("Revoked image Alice");
        TestUser bob = createUser("Revoked image Bob");
        String aliceToken = login(alice.accountNo(), "revoked-image-alice", "PC");
        String bobToken = login(bob.accountNo(), "revoked-image-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        SentImage expired = sendImage(
                bobToken,
                conversationId,
                png(600, 300, 9),
                "expired.png");
        SentImage recalled = sendImage(
                bobToken,
                conversationId,
                png(600, 300, 10),
                "recalled.png");
        jdbc.sql("""
                        UPDATE media
                        SET state = 'EXPIRED',
                            attached_message_id = NULL,
                            bound_at = NULL,
                            expired_at = CURRENT_TIMESTAMP
                        WHERE id = :mediaId
                        """)
                .param("mediaId", expired.mediaId())
                .update();
        assertThat(exchange(
                "/api/v1/messages/" + recalled.messageId() + "/recall",
                HttpMethod.POST,
                bobToken,
                null).getStatusCode()).isEqualTo(HttpStatus.OK);

        when(provider.supportsVision()).thenReturn(true);
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            assertThat(context.images()).isEmpty();
            assertThat(context.messages()).singleElement().satisfies(message -> {
                assertThat(message.messageId()).isEqualTo(expired.messageId());
                assertThat(message.text()).isEqualTo("[图片]");
                assertThat(message.mediaId()).isNull();
            });
            return withUsage(new AiSummary(
                    "Only a placeholder remains.",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(expired.messageId())));
        });

        JsonNode queued = requestSummary(aliceToken, conversationId, 2).getBody();
        awaitJob(aliceToken, UUID.fromString(queued.get("jobId").asText()));
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
    void deleting_ai_content_invalidates_every_result_copy_and_syncs_the_deletion() {
        TestUser alice = createUser("Delete AI Alice");
        TestUser bob = createUser("Delete AI Bob");
        String aliceToken = login(alice.accountNo(), "delete-ai-alice", "PC");
        String bobToken = login(bob.accountNo(), "delete-ai-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Delete every copy of this summary."));

        AtomicInteger calls = new AtomicInteger();
        when(provider.summarize(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            if (context.conversationId().equals(conversationId)) {
                calls.incrementAndGet();
            }
            return withUsage(new AiSummary(
                    "Deletable summary.",
                    List.of(),
                    List.of(),
                    List.of(),
                    context.messages().stream().map(AiContextMessage::messageId).toList()));
        });

        JsonNode first = requestSummary(aliceToken, conversationId).getBody();
        UUID firstJobId = UUID.fromString(first.get("jobId").asText());
        awaitJob(aliceToken, firstJobId);
        UUID artifactId = jdbc.sql("SELECT id FROM ai_artifacts WHERE job_id = :jobId")
                .param("jobId", firstJobId)
                .query(UUID.class)
                .single();
        JsonNode cached = requestSummary(aliceToken, conversationId).getBody();
        UUID cachedJobId = UUID.fromString(cached.get("jobId").asText());
        assertThat(cached.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(calls).hasValue(1);

        assertThat(exchange(
                "/api/v1/ai/artifacts/" + artifactId,
                HttpMethod.DELETE,
                aliceToken,
                null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_artifacts WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", alice.userId())
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM ai_jobs
                        WHERE id IN (:firstJobId, :cachedJobId)
                          AND result_json IS NOT NULL
                        """)
                .param("firstJobId", firstJobId)
                .param("cachedJobId", cachedJobId)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_cache_entries WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", alice.userId())
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM user_sync_events
                        WHERE user_id = :ownerUserId
                          AND event_type = 'AI_ARTIFACT_DELETED'
                          AND entity_id = :artifactId
                        """)
                .param("ownerUserId", alice.userId())
                .param("artifactId", artifactId)
                .query(Long.class)
                .single()).isOne();

        JsonNode regenerated = requestSummary(aliceToken, conversationId).getBody();
        UUID regeneratedJobId = UUID.fromString(regenerated.get("jobId").asText());
        awaitJob(aliceToken, regeneratedJobId);
        assertThat(calls).hasValue(2);

        assertThat(exchange(
                "/api/v1/ai/jobs/" + regeneratedJobId,
                HttpMethod.DELETE,
                aliceToken,
                null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_cache_entries WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", alice.userId())
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM user_sync_events
                        WHERE user_id = :ownerUserId
                          AND event_type = 'AI_JOB_DELETED'
                          AND entity_id = :jobId
                        """)
                .param("ownerUserId", alice.userId())
                .param("jobId", regeneratedJobId)
                .query(Long.class)
                .single()).isOne();

        JsonNode afterJobDeletion = requestSummary(aliceToken, conversationId).getBody();
        awaitJob(aliceToken, UUID.fromString(afterJobDeletion.get("jobId").asText()));
        assertThat(calls).hasValue(3);
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
            return new AiProviderResult<>(new AiSummary(
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

    @Test
    void smart_reply_request_queues_private_work_without_sending_a_conversation_message() {
        TestUser alice = createUser("Smart Reply Alice");
        TestUser bob = createUser("Smart Reply Bob");
        String aliceToken = login(alice.accountNo(), "smart-reply-alice", "PC");
        String bobToken = login(bob.accountNo(), "smart-reply-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Can you join the review tomorrow?"));

        long messagesBefore = jdbc.sql("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
                .param("conversationId", conversationId)
                .query(Long.class)
                .single();

        ResponseEntity<JsonNode> response = exchange(
                "/api/v1/conversations/" + conversationId + "/ai/smart-replies",
                HttpMethod.POST,
                aliceToken,
                Map.of("requestId", UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("kind").asText()).isEqualTo("SMART_REPLY");
        assertThat(response.getBody().get("status").asText()).isEqualTo("QUEUED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
                .param("conversationId", conversationId)
                .query(Long.class)
                .single()).isEqualTo(messagesBefore);
    }

    @Test
    void smart_reply_returns_three_editable_drafts_and_only_the_user_can_send_an_edited_copy() {
        TestUser alice = createUser("Editable Reply Alice");
        TestUser bob = createUser("Editable Reply Bob");
        String aliceToken = login(alice.accountNo(), "editable-reply-alice", "PC");
        String bobToken = login(bob.accountNo(), "editable-reply-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Can you join the review tomorrow?"));

        when(provider.smartReplies(any(AiSummaryContext.class))).thenReturn(new AiProviderResult<>(
                new AiSmartReplies(List.of(
                        new AiSmartReplies.Draft("Yes, I can join tomorrow.", "FRIENDLY"),
                        new AiSmartReplies.Draft("What time is the review?", "CURIOUS"),
                        new AiSmartReplies.Draft("I cannot make it tomorrow.", "DIRECT"))),
                31,
                12));

        JsonNode queued = exchange(
                "/api/v1/conversations/" + conversationId + "/ai/smart-replies",
                HttpMethod.POST,
                aliceToken,
                Map.of("requestId", UUID.randomUUID())).getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        awaitJob(aliceToken, jobId);

        JsonNode result = exchange(
                "/api/v1/ai/jobs/" + jobId,
                HttpMethod.GET,
                aliceToken,
                null).getBody().get("result");
        assertThat(result.get("replies")).hasSize(3);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
                .param("conversationId", conversationId)
                .query(Long.class)
                .single()).isEqualTo(1);

        JsonNode sent = post(
                "/api/v1/conversations/" + conversationId + "/messages",
                aliceToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Yes — I can join after 10:00."));
        assertThat(sent.get("senderId").asText()).isEqualTo(alice.userId().toString());
        assertThat(sent.get("text").asText()).isEqualTo("Yes — I can join after 10:00.");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
                .param("conversationId", conversationId)
                .query(Long.class)
                .single()).isEqualTo(2);
    }

    @Test
    void extraction_persists_private_action_items_and_facts_and_syncs_deletion_to_mobile_and_pc() {
        TestUser alice = createUser("Extraction Alice");
        TestUser bob = createUser("Extraction Bob");
        String alicePcToken = login(alice.accountNo(), "extraction-alice-pc", "PC");
        String aliceMobileToken = login(alice.accountNo(), "extraction-alice-mobile", "MOBILE");
        String bobToken = login(bob.accountNo(), "extraction-bob", "MOBILE");
        UUID conversationId = acceptContact(alicePcToken, bob, bobToken);
        enableAi(alicePcToken, bobToken, conversationId);
        UUID firstMessageId = UUID.fromString(post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Bob will send the proposal by Friday."))
                .get("messageId").asText());
        UUID ignoredMessageId = UUID.fromString(post(
                "/api/v1/conversations/" + conversationId + "/messages",
                alicePcToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "This message is not selected."))
                .get("messageId").asText());
        UUID thirdMessageId = UUID.fromString(post(
                "/api/v1/conversations/" + conversationId + "/messages",
                alicePcToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "The review is in meeting room 4."))
                .get("messageId").asText());

        when(provider.extractInformation(any(AiSummaryContext.class))).thenAnswer(invocation -> {
            AiSummaryContext context = invocation.getArgument(0);
            assertThat(context.messages()).extracting(AiContextMessage::messageId)
                    .containsExactly(firstMessageId, thirdMessageId)
                    .doesNotContain(ignoredMessageId);
            return new AiProviderResult<>(new AiExtraction(
                    List.of(new AiExtraction.ActionItem(
                            "Send the proposal",
                            "Send the agreed proposal before the review.",
                            bob.userId(),
                            Instant.parse("2026-08-28T09:00:00Z"),
                            "HIGH",
                            0.94,
                            List.of(firstMessageId))),
                    List.of(new AiExtraction.KeyFact(
                            "LOCATION",
                            "The review is in meeting room 4.",
                            0.99,
                            List.of(thirdMessageId)))), 44, 18);
        });

        JsonNode queued = exchange(
                "/api/v1/conversations/" + conversationId + "/ai/extract",
                HttpMethod.POST,
                alicePcToken,
                Map.of(
                        "requestId", UUID.randomUUID(),
                        "messageIds", List.of(firstMessageId, thirdMessageId))).getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        awaitJob(alicePcToken, jobId);

        JsonNode completed = exchange(
                "/api/v1/ai/jobs/" + jobId,
                HttpMethod.GET,
                alicePcToken,
                null).getBody();
        assertThat(completed.get("kind").asText()).isEqualTo("EXTRACTION");
        assertThat(completed.get("result").get("actionItems")).hasSize(1);
        assertThat(completed.get("result").get("keyFacts")).hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_action_items WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", alice.userId())
                .query(Long.class)
                .single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_action_items WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", bob.userId())
                .query(Long.class)
                .single()).isZero();

        JsonNode actionItems = exchange(
                "/api/v1/ai/action-items",
                HttpMethod.GET,
                alicePcToken,
                null).getBody();
        assertThat(actionItems).hasSize(1);
        UUID actionItemId = UUID.fromString(actionItems.get(0).get("actionItemId").asText());
        assertThat(actionItems.get(0).get("ownerUserId").asText())
                .isEqualTo(alice.userId().toString());
        assertThat(actionItems.get(0).get("assigneeUserId").asText())
                .isEqualTo(bob.userId().toString());
        assertThat(actionItems.get(0).get("status").asText()).isEqualTo("OPEN");
        JsonNode completedItem = exchange(
                "/api/v1/ai/action-items/" + actionItemId,
                HttpMethod.PATCH,
                alicePcToken,
                Map.of("status", "COMPLETED")).getBody();
        assertThat(completedItem.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completedItem.get("completedAt").isTextual()).isTrue();

        JsonNode artifacts = exchange(
                "/api/v1/ai/artifacts",
                HttpMethod.GET,
                alicePcToken,
                null).getBody();
        JsonNode extractionArtifact = null;
        for (JsonNode artifact : artifacts) {
            if (artifact.get("jobId").asText().equals(jobId.toString())) {
                extractionArtifact = artifact;
                break;
            }
        }
        assertThat(extractionArtifact).isNotNull();
        assertThat(extractionArtifact.get("content").get("actionItems")).isEmpty();
        assertThat(extractionArtifact.get("content").get("keyFacts")).hasSize(1);
        UUID artifactId = UUID.fromString(extractionArtifact.get("artifactId").asText());
        assertThat(exchange(
                "/api/v1/ai/artifacts/" + artifactId,
                HttpMethod.DELETE,
                alicePcToken,
                null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_action_items WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", alice.userId())
                .query(Long.class)
                .single()).isZero();

        for (String token : List.of(alicePcToken, aliceMobileToken)) {
            JsonNode sync = exchange("/api/v1/sync?after=0&limit=200", HttpMethod.GET, token, null).getBody();
            List<String> eventTypes = new ArrayList<>();
            sync.get("events").forEach(event -> eventTypes.add(event.get("eventType").asText()));
            assertThat(eventTypes).contains("AI_ARTIFACT_DELETED");
        }
    }

    @Test
    void rejects_extraction_evidence_outside_the_selected_authorized_context() {
        TestUser alice = createUser("Evidence Alice");
        TestUser bob = createUser("Evidence Bob");
        String aliceToken = login(alice.accountNo(), "evidence-alice", "PC");
        String bobToken = login(bob.accountNo(), "evidence-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        UUID selectedMessageId = UUID.fromString(post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Keep evidence inside this message."))
                .get("messageId").asText());

        when(provider.extractInformation(any(AiSummaryContext.class))).thenReturn(new AiProviderResult<>(
                new AiExtraction(
                        List.of(),
                        List.of(new AiExtraction.KeyFact(
                                "OTHER",
                                "Unsupported evidence",
                                0.8,
                                List.of(UUID.randomUUID())))),
                10,
                5));

        JsonNode queued = exchange(
                "/api/v1/conversations/" + conversationId + "/ai/extract",
                HttpMethod.POST,
                aliceToken,
                Map.of("requestId", UUID.randomUUID(), "messageIds", List.of(selectedMessageId)))
                .getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        awaitStatus(aliceToken, jobId, "FAILED");

        JsonNode failed = exchange(
                "/api/v1/ai/jobs/" + jobId,
                HttpMethod.GET,
                aliceToken,
                null).getBody();
        assertThat(failed.get("errorCode").asText()).isEqualTo("AI_INVALID_RESULT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_artifacts WHERE job_id = :jobId")
                .param("jobId", jobId)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_action_items WHERE source_job_id = :jobId")
                .param("jobId", jobId)
                .query(Long.class)
                .single()).isZero();
    }

    @Test
    void rejects_smart_reply_results_that_do_not_contain_exactly_three_drafts() {
        TestUser alice = createUser("Reply Count Alice");
        TestUser bob = createUser("Reply Count Bob");
        String aliceToken = login(alice.accountNo(), "reply-count-alice", "PC");
        String bobToken = login(bob.accountNo(), "reply-count-bob", "MOBILE");
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);
        enableAi(aliceToken, bobToken, conversationId);
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "Return the fixed draft count."));
        when(provider.smartReplies(any(AiSummaryContext.class))).thenReturn(new AiProviderResult<>(
                new AiSmartReplies(List.of(
                        new AiSmartReplies.Draft("First", "DIRECT"),
                        new AiSmartReplies.Draft("Second", "FRIENDLY"))),
                10,
                5));

        JsonNode queued = exchange(
                "/api/v1/conversations/" + conversationId + "/ai/smart-replies",
                HttpMethod.POST,
                aliceToken,
                Map.of("requestId", UUID.randomUUID())).getBody();
        UUID jobId = UUID.fromString(queued.get("jobId").asText());
        awaitStatus(aliceToken, jobId, "FAILED");

        JsonNode failed = exchange(
                "/api/v1/ai/jobs/" + jobId,
                HttpMethod.GET,
                aliceToken,
                null).getBody();
        assertThat(failed.get("errorCode").asText()).isEqualTo("AI_INVALID_RESULT");
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
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_action_items WHERE owner_user_id = :ownerUserId")
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

    private SentImage sendImage(
            String token,
            UUID conversationId,
            byte[] content,
            String filename
    ) {
        UUID uploadId = UUID.randomUUID();
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new NamedByteArrayResource(content, filename));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> uploaded = http.exchange(
                "/api/v1/media/images?uploadId=" + uploadId,
                HttpMethod.POST,
                new HttpEntity<>(parts, headers),
                JsonNode.class);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID mediaId = UUID.fromString(uploaded.getBody().get("mediaId").asText());
        JsonNode sent = post(
                "/api/v1/conversations/" + conversationId + "/messages",
                token,
                Map.of(
                        "clientMsgId", UUID.randomUUID(),
                        "type", "IMAGE",
                        "mediaId", mediaId));
        return new SentImage(
                UUID.fromString(sent.get("messageId").asText()),
                mediaId);
    }

    private byte[] png(int width, int height, int colorSeed) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, new Color(
                        (x + colorSeed) % 255,
                        (y + colorSeed) % 255,
                        (x + y + colorSeed) % 255).getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
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

    private AiProviderResult<AiSummary> withUsage(AiSummary summary) {
        return new AiProviderResult<>(summary, 37, 5);
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

    private record SentImage(UUID messageId, UUID mediaId) {
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] content, String filename) {
            super(content);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
