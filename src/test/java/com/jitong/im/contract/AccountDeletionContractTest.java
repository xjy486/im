package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountDeletionContractTest extends ContractTestEnvironment {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void refuses_deletion_while_the_user_still_owns_a_group() throws Exception {
        TestUser owner = createUser("Deletion owner");
        String token = login(owner.accountNo(), "deletion-owner");
        JsonNode group = exchange(
                HttpMethod.POST,
                "/api/v1/groups",
                token,
                Map.of("name", "Still owned", "visibility", "PRIVATE")).getBody();

        ResponseEntity<JsonNode> response = deleteAccount(token, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText())
                .isEqualTo("ACCOUNT_DELETION_GROUP_OWNER");
        assertThat(jdbc.sql("SELECT status FROM users WHERE id = :userId")
                .param("userId", owner.userId())
                .query(String.class)
                .single()).isEqualTo("ACTIVE");
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/groups",
                token,
                null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(group.get("ownerUserId").asText()).isEqualTo(owner.userId().toString());
    }

    @Test
    void deletion_anonymizes_c2c_history_and_erases_server_credentials_and_relationships()
            throws Exception {
        TestUser alice = createUser("Deletion Alice");
        TestUser bob = createUser("Deletion Bob");
        String aliceToken = login(alice.accountNo(), "deletion-alice");
        String bobToken = login(bob.accountNo(), "deletion-bob");
        UUID conversationId = acceptContact(aliceToken, alice, bobToken, bob);
        JsonNode message = exchange(
                HttpMethod.POST,
                "/api/v1/conversations/" + conversationId + "/messages",
                aliceToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "lawful history"))
                .getBody();

        ResponseEntity<JsonNode> deleted = deleteAccount(aliceToken, PASSWORD);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(
                HttpMethod.POST,
                "/api/v1/auth/validate",
                aliceToken,
                null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/users/search?accountNo=" + alice.accountNo(),
                bobToken,
                null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/contacts",
                bobToken,
                null).getBody()).isEmpty();

        JsonNode conversation = exchange(
                HttpMethod.GET,
                "/api/v1/conversations",
                bobToken,
                null).getBody().get(0);
        assertThat(conversation.get("status").asText()).isEqualTo("READ_ONLY");
        assertThat(conversation.get("peerDisplayName").asText()).isEqualTo("已注销用户");

        JsonNode history = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/messages?afterSeq=0&limit=200",
                bobToken,
                null).getBody().get("messages").get(0);
        assertThat(history.get("messageId").asText()).isEqualTo(message.get("messageId").asText());
        assertThat(history.get("senderDisplayName").asText()).isEqualTo("已注销用户");
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/users/" + alice.userId() + "/profile",
                bobToken,
                null).getBody().get("displayName").asText()).isEqualTo("已注销用户");

        assertThat(jdbc.sql("SELECT status, searchable_by_account_no, display_name FROM users WHERE id = :userId")
                .param("userId", alice.userId())
                .query((row, rowNum) -> Map.of(
                        "status", row.getString("status"),
                        "searchable", row.getBoolean("searchable_by_account_no"),
                        "displayName", row.getString("display_name")))
                .single())
                .containsEntry("status", "DELETED")
                .containsEntry("searchable", false)
                .containsEntry("displayName", "已注销用户");
        assertThat(jdbc.sql("SELECT retired_at FROM public_identifiers WHERE public_no = :accountNo")
                .param("accountNo", alice.accountNo())
                .query(Object.class)
                .single()).isNotNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM devices WHERE user_id = :userId")
                .param("userId", alice.userId())
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM ai_jobs
                        WHERE owner_user_id = :userId
                          AND status IN ('QUEUED', 'RUNNING')
                        """)
                .param("userId", alice.userId())
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM auth_sessions WHERE user_id = :userId")
                .param("userId", alice.userId())
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM contacts
                        WHERE user_low_id = :userId OR user_high_id = :userId
                        """)
                .param("userId", alice.userId())
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM conversation_members
                        WHERE user_id = :userId
                        """)
                .param("userId", alice.userId())
                .query(Long.class)
                .single()).isZero();
    }

    private TestUser createUser(String displayName) throws Exception {
        HttpHeaders headers = jsonHeaders();
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        ResponseEntity<JsonNode> response = http.postForEntity(
                "/api/v1/admin/users",
                new HttpEntity<>(
                        objectMapper.writeValueAsString(Map.of(
                                "displayName", displayName + UUID.randomUUID(),
                                "password", PASSWORD)),
                        headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new TestUser(
                UUID.fromString(response.getBody().get("userId").asText()),
                response.getBody().get("accountNo").asText());
    }

    private String login(String accountNo, String installationId) throws Exception {
        ResponseEntity<JsonNode> response = http.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        objectMapper.writeValueAsString(Map.of(
                                "accountNo", accountNo,
                                "password", PASSWORD,
                                "deviceClass", "PC",
                                "installationId", installationId)),
                        jsonHeaders()),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("accessToken").asText();
    }

    private UUID acceptContact(
            String requesterToken,
            TestUser requester,
            String recipientToken,
            TestUser recipient
    ) {
        JsonNode request = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests",
                requesterToken,
                Map.of("accountNo", recipient.accountNo(), "verification", "history"))
                .getBody();
        return UUID.fromString(exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + request.get("requestId").asText() + "/accept",
                recipientToken,
                null).getBody().get("conversationId").asText());
    }

    private ResponseEntity<JsonNode> deleteAccount(String token, String password) throws Exception {
        return exchange(
                HttpMethod.DELETE,
                "/api/v1/auth/account",
                token,
                Map.of("currentPassword", password));
    }

    private ResponseEntity<JsonNode> exchange(
            HttpMethod method,
            String path,
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
                new HttpEntity<>(body == null ? null : toJson(body), headers),
                JsonNode.class);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private record TestUser(UUID userId, String accountNo) {
    }
}
