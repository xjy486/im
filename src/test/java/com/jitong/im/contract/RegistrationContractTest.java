package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void public_registration_allocates_account_number_and_starts_a_mobile_session() throws Exception {
        ResponseEntity<JsonNode> response = http.postForEntity(
                "/api/v1/auth/register",
                new HttpEntity<>(
                        objectMapper.writeValueAsString(Map.of(
                                "displayName", "New user",
                                "password", "correct horse battery staple",
                                "deviceClass", "MOBILE",
                                "installationId", "new-user-installation")),
                        jsonHeaders()),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();

        UUID userId = UUID.fromString(body.get("userId").asText());
        String accountNo = body.get("accountNo").asText();
        String accessToken = body.get("accessToken").asText();

        assertThat(accountNo)
                .hasSize(11)
                .matches("[1-9][0-9]{10}")
                .satisfies(number -> assertThat(com.jitong.im.auth.PublicNumber.isValid(number)).isTrue());
        assertThat(body.get("deviceClass").asText()).isEqualTo("MOBILE");
        assertThat(accessToken).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();

        assertThat(jdbc.sql("""
                        SELECT display_name
                        FROM users
                        WHERE id = :userId
                        """)
                .param("userId", userId)
                .query(String.class)
                .single()).isEqualTo("New user");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM devices
                        WHERE user_id = :userId
                          AND device_class = 'MOBILE'
                          AND trust_state = 'ACTIVE'
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM audit_logs
                        WHERE event_type = 'REGISTER'
                          AND outcome = 'SUCCEEDED'
                          AND subject_id = :userId
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single()).isEqualTo(1L);

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(accessToken);
        ResponseEntity<JsonNode> protectedResponse = http.exchange(
                "/api/v1/protected/test",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                JsonNode.class);

        assertThat(protectedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(protectedResponse.getBody().get("authenticated").asBoolean()).isTrue();
    }

    @Test
    void registration_rejects_invalid_input_without_creating_a_user() throws Exception {
        long usersBefore = jdbc.sql("SELECT COUNT(*) FROM users")
                .query(Long.class)
                .single();

        ResponseEntity<JsonNode> response = http.postForEntity(
                "/api/v1/auth/register",
                new HttpEntity<>(
                        objectMapper.writeValueAsString(Map.of(
                                "displayName", " ",
                                "password", "short")),
                        jsonHeaders()),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM users")
                .query(Long.class)
                .single()).isEqualTo(usersBefore);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
