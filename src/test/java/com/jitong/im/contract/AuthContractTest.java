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

import static org.assertj.core.api.Assertions.assertThat;

class AuthContractTest extends ContractTestEnvironment {

    private static final String ADMIN_KEY = ContractDependencies.ADMIN_API_KEY;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void admin_creates_a_preset_user_and_login_returns_opaque_tokens_that_call_protected_api() throws Exception {
        ResponseEntity<String> created = createUser("Alice");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode user = objectMapper.readTree(created.getBody());
        String userId = user.get("userId").asText();
        String accountNo = user.get("accountNo").asText();

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> login = http.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(
                        json(Map.of("accountNo", accountNo, "password", "correct horse battery staple")),
                        loginHeaders),
                String.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode tokens = objectMapper.readTree(login.getBody());
        String accessToken = tokens.get("accessToken").asText();
        String refreshToken = tokens.get("refreshToken").asText();
        assertThat(accessToken).isNotBlank().doesNotContain(accountNo);
        assertThat(refreshToken).isNotBlank().doesNotContain(accountNo);
        assertThat(tokens.get("accessTokenExpiresAt").asText()).isNotBlank();
        assertThat(tokens.get("refreshTokenExpiresAt").asText()).isNotBlank();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM audit_logs
                        WHERE event_type = 'LOGIN'
                          AND outcome = 'SUCCEEDED'
                          AND subject_id = :userId
                        """)
                .param("userId", java.util.UUID.fromString(userId))
                .query(Long.class)
                .single()).isEqualTo(1L);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<JsonNode> protectedResponse = http.exchange(
                "/api/v1/protected/test",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);

        assertThat(protectedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(protectedResponse.getBody().get("authenticated").asBoolean()).isTrue();
    }

    @Test
    void wrong_credentials_have_one_failure_shape_and_are_rate_limited_by_account_and_ip() throws Exception {
        String accountNo = objectMapper.readTree(createUser("Bob").getBody()).get("accountNo").asText();

        ResponseEntity<JsonNode> firstFailure = login(accountNo, "wrong password");
        assertThat(firstFailure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(firstFailure.getBody().fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("version", "code", "message", "requestId", "timestamp");
        assertThat(firstFailure.getBody().get("code").asText()).isEqualTo("AUTH_INVALID");

        login(accountNo, "wrong password");
        login(accountNo, "wrong password");
        login(accountNo, "wrong password");
        login(accountNo, "wrong password");
        ResponseEntity<JsonNode> limited = login(accountNo, "wrong password");
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code").asText()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void admin_endpoint_rejects_missing_or_wrong_api_key() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = http.exchange(
                "/api/v1/admin/users",
                HttpMethod.POST,
                new HttpEntity<>(json(Map.of("displayName", "Nope", "password", "password123")), headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("FORBIDDEN");
    }

    private ResponseEntity<String> createUser(String displayName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Api-Key", ADMIN_KEY);
        return http.postForEntity(
                "/api/v1/admin/users",
                new HttpEntity<>(json(Map.of(
                        "displayName", displayName,
                        "password", "correct horse battery staple")), headers),
                String.class);
    }

    private ResponseEntity<JsonNode> login(String accountNo, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(json(Map.of("accountNo", accountNo, "password", password)), headers),
                JsonNode.class);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
