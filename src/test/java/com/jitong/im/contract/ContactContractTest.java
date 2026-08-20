package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContactContractTest extends ContractTestEnvironment {

    private static final String ADMIN_KEY = ContractDependencies.ADMIN_API_KEY;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exact_search_and_cross_requests_create_one_reusable_c2c_conversation() throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        String aliceToken = login(alice.accountNo(), "alice-installation");
        String bobToken = login(bob.accountNo(), "bob-installation");

        ResponseEntity<JsonNode> exactSearch = exchange(
                HttpMethod.GET,
                "/api/v1/users/search?accountNo=" + bob.accountNo(),
                aliceToken,
                null);
        assertThat(exactSearch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exactSearch.getBody().fieldNames()).toIterable()
                .containsExactlyInAnyOrder(
                        "version", "accountNo", "displayName",
                        "avatarUrl", "relationship", "pendingRequestId");
        assertThat(exactSearch.getBody().get("accountNo").asText()).isEqualTo(bob.accountNo());

        ResponseEntity<JsonNode> prefixSearch = exchange(
                HttpMethod.GET,
                "/api/v1/users/search?accountNo=" + bob.accountNo().substring(0, 4),
                aliceToken,
                null);
        assertThat(prefixSearch.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode outgoing = createRequest(aliceToken, bob.accountNo());
        assertThat(outgoing.get("status").asText()).isEqualTo("PENDING");
        UUID firstRequestId = UUID.fromString(outgoing.get("requestId").asText());

        JsonNode crossRequest = createRequest(bobToken, alice.accountNo());
        assertThat(crossRequest.get("status").asText()).isEqualTo("ACCEPTED");
        String conversationId = crossRequest.get("conversationId").asText();

        ResponseEntity<JsonNode> contacts = exchange(HttpMethod.GET, "/api/v1/contacts", aliceToken, null);
        assertThat(contacts.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contacts.getBody()).hasSize(1);
        assertThat(contacts.getBody().get(0).get("conversationId").asText()).isEqualTo(conversationId);

        ResponseEntity<JsonNode> conversations = exchange(
                HttpMethod.GET,
                "/api/v1/conversations",
                bobToken,
                null);
        assertThat(conversations.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(conversations.getBody()).hasSize(1);
        assertThat(conversations.getBody().get(0).get("conversationId").asText()).isEqualTo(conversationId);

        ResponseEntity<JsonNode> oldRequest = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + firstRequestId + "/accept",
                bobToken,
                null);
        assertThat(oldRequest.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void disabling_account_discovery_hides_exact_search_and_prevents_new_requests() throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        String aliceToken = login(alice.accountNo(), "alice-installation");
        String bobToken = login(bob.accountNo(), "bob-installation");

        ResponseEntity<Void> disabled = exchangeVoid(
                HttpMethod.POST,
                "/api/v1/users/me/searchability",
                bobToken,
                Map.of("searchable", false));
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> search = exchange(
                HttpMethod.GET,
                "/api/v1/users/search?accountNo=" + bob.accountNo(),
                aliceToken,
                null);
        assertThat(search.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> request = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests",
                aliceToken,
                Map.of("accountNo", bob.accountNo(), "verification", "hidden"));
        assertThat(request.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void remove_and_readd_preserve_readonly_history_and_reuse_conversation() throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        String aliceToken = login(alice.accountNo(), "alice-installation");
        String bobToken = login(bob.accountNo(), "bob-installation");

        JsonNode request = createRequest(aliceToken, bob.accountNo());
        UUID requestId = UUID.fromString(request.get("requestId").asText());
        ResponseEntity<JsonNode> acceptedResponse = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + requestId + "/accept",
                bobToken,
                null);
        assertThat(acceptedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode accepted = acceptedResponse.getBody();
        String conversationId = accepted.get("conversationId").asText();

        ResponseEntity<Void> removed = exchangeVoid(
                HttpMethod.DELETE,
                "/api/v1/contacts/" + bob.userId(),
                aliceToken);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode readonly = exchange(HttpMethod.GET, "/api/v1/conversations", aliceToken, null).getBody().get(0);
        assertThat(readonly.get("conversationId").asText()).isEqualTo(conversationId);
        assertThat(readonly.get("status").asText()).isEqualTo("READ_ONLY");

        JsonNode readded = createRequest(bobToken, alice.accountNo());
        UUID readdRequestId = UUID.fromString(readded.get("requestId").asText());
        JsonNode reaccepted = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + readdRequestId + "/accept",
                aliceToken,
                null).getBody();
        assertThat(reaccepted.get("conversationId").asText()).isEqualTo(conversationId);
    }

    @Test
    void block_cancels_pending_requests_and_unblock_does_not_restore_them() throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        String aliceToken = login(alice.accountNo(), "alice-installation");
        String bobToken = login(bob.accountNo(), "bob-installation");

        JsonNode request = createRequest(aliceToken, bob.accountNo());
        UUID requestId = UUID.fromString(request.get("requestId").asText());
        assertThat(request.get("status").asText()).isEqualTo("PENDING");

        assertThat(exchangeVoid(
                HttpMethod.POST,
                "/api/v1/blocks/" + alice.userId(),
                bobToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchangeVoid(
                HttpMethod.DELETE,
                "/api/v1/blocks/" + alice.userId(),
                bobToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode requests = exchange(
                HttpMethod.GET,
                "/api/v1/contact-requests",
                aliceToken,
                null).getBody();
        JsonNode cancelled = null;
        for (JsonNode item : requests) {
            if (item.get("requestId").asText().equals(requestId.toString())) {
                cancelled = item;
                break;
            }
        }
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");

        ResponseEntity<JsonNode> reRequest = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests",
                aliceToken,
                Map.of("accountNo", bob.accountNo(), "verification", "after unblock"));
        assertThat(reRequest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reRequest.getBody().get("status").asText()).isEqualTo("PENDING");

        JsonNode conversations = exchange(
                HttpMethod.GET,
                "/api/v1/conversations",
                bobToken,
                null).getBody();
        assertThat(conversations).isEmpty();
    }

    @Test
    void recipient_can_reject_and_requester_can_cancel_pending_requests() throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        String aliceToken = login(alice.accountNo(), "alice-installation");
        String bobToken = login(bob.accountNo(), "bob-installation");

        JsonNode rejectedRequest = createRequest(aliceToken, bob.accountNo());
        UUID rejectedRequestId = UUID.fromString(rejectedRequest.get("requestId").asText());
        ResponseEntity<JsonNode> rejected = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + rejectedRequestId + "/reject",
                bobToken,
                null);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody().get("status").asText()).isEqualTo("REJECTED");

        JsonNode cancelledRequest = createRequest(aliceToken, bob.accountNo());
        UUID cancelledRequestId = UUID.fromString(cancelledRequest.get("requestId").asText());
        ResponseEntity<JsonNode> cancelled = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + cancelledRequestId + "/cancel",
                aliceToken,
                null);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asText()).isEqualTo("CANCELLED");
    }

    private TestUser createUser(String displayName) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Api-Key", ADMIN_KEY);
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(json(Map.of(
                        "accountNo", accountNo,
                        "password", "correct horse battery staple",
                        "installationId", installationId)), headers),
                String.class);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private JsonNode createRequest(String token, String accountNo) throws Exception {
        return exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests",
                token,
                Map.of("accountNo", accountNo, "verification", "hello")).getBody();
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
        return http.exchange(path, method, new HttpEntity<>(body == null ? null : json(body), headers), JsonNode.class);
    }

    private ResponseEntity<Void> exchangeVoid(HttpMethod method, String path, String token) {
        return exchangeVoid(method, path, token, null);
    }

    private ResponseEntity<Void> exchangeVoid(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(
                path,
                method,
                new HttpEntity<>(body == null ? null : json(body), headers),
                Void.class);
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
