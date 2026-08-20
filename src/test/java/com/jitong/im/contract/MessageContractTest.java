package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MessageContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    @Test
    void websocket_acknowledges_the_sender_and_delivers_the_committed_message_to_the_peer() throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        String aliceToken = login(alice.accountNo(), "alice-websocket-installation");
        String bobToken = login(bob.accountNo(), "bob-websocket-installation");
        JsonNode request = post(
                "/api/v1/contact-requests",
                aliceToken,
                Map.of("accountNo", bob.accountNo(), "verification", ""));
        UUID requestId = UUID.fromString(request.get("requestId").asText());
        UUID conversationId = UUID.fromString(exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + requestId + "/accept",
                bobToken,
                null).getBody().get("conversationId").asText());

        CountDownLatch ack = new CountDownLatch(1);
        CountDownLatch peerEvent = new CountDownLatch(1);
        AtomicReference<JsonNode> ackBody = new AtomicReference<>();
        AtomicReference<JsonNode> peerBody = new AtomicReference<>();
        OkHttpClient client = new OkHttpClient();
        WebSocket peer = openWebSocket(client, bobToken, operation -> {
            if ("message.created".equals(operation.get("operation").asText())) {
                peerBody.set(operation.get("body"));
                peerEvent.countDown();
            }
        });
        WebSocket sender = openWebSocket(client, aliceToken, operation -> {
            if ("message.ack".equals(operation.get("operation").asText())) {
                ackBody.set(operation.get("body"));
                ack.countDown();
            }
        });
        UUID clientMsgId = UUID.randomUUID();
        sender.send(json(Map.of(
                "version", 1,
                "operation", "message.send",
                "requestId", UUID.randomUUID(),
                "body", Map.of(
                        "conversationId", conversationId,
                        "clientMsgId", clientMsgId,
                        "text", "websocket hello"))));

        assertThat(ack.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(peerEvent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ackBody.get().get("clientMsgId").asText()).isEqualTo(clientMsgId.toString());
        assertThat(peerBody.get().get("conversationSeq").asLong()).isEqualTo(1);
        assertThat(peerBody.get().get("text").asText()).isEqualTo("websocket hello");
        sender.close(1000, "test complete");
        peer.close(1000, "test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    void sends_idempotent_ordered_text_and_preserves_history_after_contact_removal() throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        String aliceToken = login(alice.accountNo(), "alice-message-installation");
        String bobToken = login(bob.accountNo(), "bob-message-installation");

        JsonNode request = post(
                "/api/v1/contact-requests",
                aliceToken,
                Map.of("accountNo", bob.accountNo(), "verification", "hello"));
        UUID requestId = UUID.fromString(request.get("requestId").asText());
        JsonNode accepted = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + requestId + "/accept",
                bobToken,
                null).getBody();
        UUID conversationId = UUID.fromString(accepted.get("conversationId").asText());
        UUID clientMsgId = UUID.randomUUID();

        JsonNode first = post(
                "/api/v1/conversations/" + conversationId + "/messages",
                aliceToken,
                Map.of("clientMsgId", clientMsgId, "text", "first"));
        JsonNode retry = post(
                "/api/v1/conversations/" + conversationId + "/messages",
                aliceToken,
                Map.of("clientMsgId", clientMsgId, "text", "first"));
        JsonNode second = post(
                "/api/v1/conversations/" + conversationId + "/messages",
                aliceToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "second"));

        assertThat(first.get("conversationSeq").asLong()).isEqualTo(1);
        assertThat(retry.get("messageId").asText()).isEqualTo(first.get("messageId").asText());
        assertThat(retry.get("conversationSeq").asLong()).isEqualTo(1);
        assertThat(second.get("conversationSeq").asLong()).isEqualTo(2);

        JsonNode history = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/messages?afterSeq=0&limit=200",
                bobToken,
                null).getBody();
        assertThat(history.get("messages")).hasSize(2);
        assertThat(history.get("messages").get(0).get("conversationSeq").asLong()).isEqualTo(1);
        assertThat(history.get("messages").get(1).get("conversationSeq").asLong()).isEqualTo(2);

        assertThat(exchangeVoid(
                HttpMethod.DELETE,
                "/api/v1/contacts/" + bob.userId(),
                aliceToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<JsonNode> rejectedRetry = exchange(
                HttpMethod.POST,
                "/api/v1/conversations/" + conversationId + "/messages",
                aliceToken,
                Map.of("clientMsgId", clientMsgId, "text", "first"));
        assertThat(rejectedRetry.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejectedRetry.getBody().get("code").asText()).isEqualTo("NOT_CONTACT");
    }

    @Test
    void rejects_text_and_frame_boundary_with_stable_errors() throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        String aliceToken = login(alice.accountNo(), "alice-limit-installation");
        String bobToken = login(bob.accountNo(), "bob-limit-installation");
        JsonNode request = post(
                "/api/v1/contact-requests",
                aliceToken,
                Map.of("accountNo", bob.accountNo(), "verification", ""));
        UUID requestId = UUID.fromString(request.get("requestId").asText());
        UUID conversationId = UUID.fromString(exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + requestId + "/accept",
                bobToken,
                null).getBody().get("conversationId").asText());

        ResponseEntity<JsonNode> tooLong = exchange(
                HttpMethod.POST,
                "/api/v1/conversations/" + conversationId + "/messages",
                aliceToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "x".repeat(4001)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody().get("code").asText()).isEqualTo("TEXT_TOO_LONG");
    }

    @Test
    void keeps_each_device_cursor_independent_while_syncing_the_same_message_stream() throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        String aliceMobileToken = login(alice.accountNo(), "alice-sync-mobile", "MOBILE");
        String alicePcToken = login(alice.accountNo(), "alice-sync-pc", "PC");
        String bobToken = login(bob.accountNo(), "bob-sync-mobile", "MOBILE");

        JsonNode request = post(
                "/api/v1/contact-requests",
                aliceMobileToken,
                Map.of("accountNo", bob.accountNo(), "verification", ""));
        UUID requestId = UUID.fromString(request.get("requestId").asText());
        UUID conversationId = UUID.fromString(exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + requestId + "/accept",
                bobToken,
                null).getBody().get("conversationId").asText());

        for (String text : List.of("one", "two", "three")) {
            post(
                    "/api/v1/conversations/" + conversationId + "/messages",
                    aliceMobileToken,
                    Map.of("clientMsgId", UUID.randomUUID(), "text", text));
        }

        JsonNode mobilePage = exchange(
                HttpMethod.GET,
                "/api/v1/sync?after=0&until=3",
                aliceMobileToken,
                null).getBody();
        assertThat(mobilePage.get("events")).hasSize(3);
        assertThat(mobilePage.get("nextAfterSeq").asLong()).isEqualTo(3);

        JsonNode mobileAck = exchange(
                HttpMethod.POST,
                "/api/v1/sync/ack",
                aliceMobileToken,
                Map.of("syncSeq", 3)).getBody();
        assertThat(mobileAck.get("ackedSeq").asLong()).isEqualTo(3);

        JsonNode pcPage = exchange(
                HttpMethod.GET,
                "/api/v1/sync?after=0&until=3",
                alicePcToken,
                null).getBody();
        assertThat(pcPage.get("events")).hasSize(3);
        assertThat(pcPage.get("events").get(0).get("syncSeq").asLong()).isEqualTo(1);
        assertThat(pcPage.get("events").get(2).get("syncSeq").asLong()).isEqualTo(3);
    }

    @Test
    void maintains_read_progress_per_user_and_does_not_advance_it_from_sync_or_notifications() throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        String aliceMobileToken = login(alice.accountNo(), "alice-read-mobile", "MOBILE");
        String alicePcToken = login(alice.accountNo(), "alice-read-pc", "PC");
        String bobToken = login(bob.accountNo(), "bob-read-mobile", "MOBILE");

        JsonNode request = post(
                "/api/v1/contact-requests",
                aliceMobileToken,
                Map.of("accountNo", bob.accountNo(), "verification", ""));
        UUID requestId = UUID.fromString(request.get("requestId").asText());
        UUID conversationId = UUID.fromString(exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + requestId + "/accept",
                bobToken,
                null).getBody().get("conversationId").asText());

        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "read me"));

        JsonNode beforeRead = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/read",
                alicePcToken,
                null).getBody();
        assertThat(readSeqFor(beforeRead, alice.userId())).isZero();
        assertThat(readSeqFor(beforeRead, bob.userId())).isZero();

        JsonNode marked = post(
                "/api/v1/conversations/" + conversationId + "/read",
                aliceMobileToken,
                Map.of("readSeq", 1));
        assertThat(readSeqFor(marked, alice.userId())).isEqualTo(1);

        JsonNode pcView = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/read",
                alicePcToken,
                null).getBody();
        assertThat(readSeqFor(pcView, alice.userId())).isEqualTo(1);
        assertThat(readSeqFor(pcView, bob.userId())).isZero();

        JsonNode peerView = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/read",
                bobToken,
                null).getBody();
        assertThat(readSeqFor(peerView, alice.userId())).isEqualTo(1);

        JsonNode older = post(
                "/api/v1/conversations/" + conversationId + "/read",
                alicePcToken,
                Map.of("readSeq", 0));
        assertThat(readSeqFor(older, alice.userId())).isEqualTo(1);

        JsonNode syncBeforeOpen = exchange(
                HttpMethod.GET,
                "/api/v1/sync?after=0&until=1",
                alicePcToken,
                null).getBody();
        assertThat(syncBeforeOpen.get("events")).hasSize(1);
        assertThat(syncBeforeOpen.get("events").get(0).get("eventType").asText())
                .isEqualTo("MESSAGE_CREATED");
    }

    @Test
    void broadcasts_one_user_level_read_event_to_the_other_device_and_peer() throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        String aliceMobileToken = login(alice.accountNo(), "alice-read-broadcast-mobile", "MOBILE");
        String alicePcToken = login(alice.accountNo(), "alice-read-broadcast-pc", "PC");
        String bobToken = login(bob.accountNo(), "bob-read-broadcast-mobile", "MOBILE");

        JsonNode request = post(
                "/api/v1/contact-requests",
                aliceMobileToken,
                Map.of("accountNo", bob.accountNo(), "verification", ""));
        UUID requestId = UUID.fromString(request.get("requestId").asText());
        UUID conversationId = UUID.fromString(exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + requestId + "/accept",
                bobToken,
                null).getBody().get("conversationId").asText());
        post(
                "/api/v1/conversations/" + conversationId + "/messages",
                bobToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "broadcast me"));

        CountDownLatch pcRead = new CountDownLatch(1);
        CountDownLatch peerRead = new CountDownLatch(1);
        AtomicReference<JsonNode> pcBody = new AtomicReference<>();
        AtomicReference<JsonNode> peerBody = new AtomicReference<>();
        OkHttpClient client = new OkHttpClient();
        WebSocket pc = openWebSocket(client, alicePcToken, operation -> {
            if ("conversation.read".equals(operation.get("operation").asText())
                    && operation.get("body").get("userId").asText().equals(alice.userId().toString())) {
                pcBody.set(operation.get("body"));
                pcRead.countDown();
            }
        });
        WebSocket peer = openWebSocket(client, bobToken, operation -> {
            if ("conversation.read".equals(operation.get("operation").asText())
                    && operation.get("body").get("userId").asText().equals(alice.userId().toString())) {
                peerBody.set(operation.get("body"));
                peerRead.countDown();
            }
        });

        JsonNode marked = post(
                "/api/v1/conversations/" + conversationId + "/read",
                aliceMobileToken,
                Map.of("readSeq", 1));

        assertThat(readSeqFor(marked, alice.userId())).isEqualTo(1);
        assertThat(pcRead.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(peerRead.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pcBody.get().get("readSeq").asLong()).isEqualTo(1);
        assertThat(peerBody.get().get("readSeq").asLong()).isEqualTo(1);
        assertThat(pcBody.get().has("deviceId")).isFalse();
        assertThat(peerBody.get().has("deviceClass")).isFalse();

        pc.close(1000, "test complete");
        peer.close(1000, "test complete");
        client.dispatcher().executorService().shutdown();
    }

    private JsonNode post(String path, String token, Object body) throws Exception {
        return exchange(HttpMethod.POST, path, token, body).getBody();
    }

    private long readSeqFor(JsonNode page, UUID userId) {
        for (JsonNode state : page.get("states")) {
            if (state.get("userId").asText().equals(userId.toString())) {
                return state.get("readSeq").asLong();
            }
        }
        throw new AssertionError("Missing read state for " + userId);
    }

    private WebSocket openWebSocket(
            OkHttpClient client,
            String token,
            java.util.function.Consumer<JsonNode> onMessage
    ) throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        WebSocket socket = client.newWebSocket(
                new Request.Builder()
                        .url("ws://127.0.0.1:" + port + "/api/v1/ws")
                        .header("Authorization", "Bearer " + token)
                        .build(),
                new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, Response response) {
                        opened.countDown();
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, String text) {
                        try {
                            onMessage.accept(objectMapper.readTree(text));
                        } catch (Exception exception) {
                            failure.set(exception);
                        }
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
                        failure.set(throwable);
                        opened.countDown();
                    }
                });
        assertThat(opened.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        return socket;
    }

    private ResponseEntity<JsonNode> exchange(
            HttpMethod method,
            String path,
            String token,
            Object body
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(
                path,
                method,
                new HttpEntity<>(body == null ? null : json(body), headers),
                JsonNode.class);
    }

    private ResponseEntity<Void> exchangeVoid(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, method, new HttpEntity<>(headers), Void.class);
    }

    private TestUser createUser(String displayName) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        ResponseEntity<String> response = http.postForEntity(
                "/api/v1/admin/users",
                new HttpEntity<>(json(Map.of(
                        "displayName", displayName + UUID.randomUUID(),
                        "password", "correct horse battery staple")), headers),
                String.class);
        JsonNode body = objectMapper.readTree(response.getBody());
        return new TestUser(
                UUID.fromString(body.get("userId").asText()),
                body.get("accountNo").asText());
    }

    private String login(String accountNo, String installationId) throws Exception {
        return login(accountNo, installationId, "MOBILE");
    }

    private String login(String accountNo, String installationId, String deviceClass) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(json(Map.of(
                        "accountNo", accountNo,
                        "password", "correct horse battery staple",
                        "installationId", installationId,
                        "deviceClass", deviceClass)), headers),
                String.class);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record TestUser(UUID userId, String accountNo) {
    }
}
