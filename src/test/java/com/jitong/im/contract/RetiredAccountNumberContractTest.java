package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.auth.PublicNumberGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Import(RetiredAccountNumberContractTest.FixedNumberGeneratorConfiguration.class)
class RetiredAccountNumberContractTest extends ContractTestEnvironment {

    private static final String RETIRED_NUMBER = "79927398713";
    private static final String REPLACEMENT_NUMBER = "12345678903";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void retired_account_numbers_remain_reserved_and_cannot_be_reused() throws Exception {
        JsonNode created = createUser("Retired");
        UUID userId = UUID.fromString(created.get("userId").asText());
        String accountNo = created.get("accountNo").asText();
        ResponseEntity<JsonNode> beforeRetirementLogin = login(
                accountNo,
                "correct horse battery staple");
        assertThat(beforeRetirementLogin.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> retired = http.exchange(
                "/api/v1/admin/users/" + userId + "/retire",
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders()),
                JsonNode.class);

        assertThat(retired.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<JsonNode> repeatedRetirement = http.exchange(
                "/api/v1/admin/users/" + userId + "/retire",
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders()),
                JsonNode.class);
        assertThat(repeatedRetirement.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(repeatedRetirement.getBody().get("code").asText()).isEqualTo("CONFLICT");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM audit_logs
                        WHERE event_type = 'ACCOUNT_DELETION'
                          AND subject_id = :userId
                          AND outcome = 'REJECTED'
                          AND error_code = 'CONFLICT'
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single()).isEqualTo(1L);
        ResponseEntity<JsonNode> missingRetirement = http.exchange(
                "/api/v1/admin/users/" + UUID.randomUUID() + "/retire",
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders()),
                JsonNode.class);
        assertThat(missingRetirement.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missingRetirement.getBody().get("code").asText()).isEqualTo("USER_NOT_FOUND");
        assertThat(jdbc.sql("SELECT status FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single()).isEqualTo("DELETED");
        assertThat(jdbc.sql("""
                        SELECT retired_at
                        FROM public_identifiers
                        WHERE public_no = :accountNo
                        """)
                .param("accountNo", accountNo)
                .query(Object.class)
                .single()).isNotNull();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM refresh_tokens r
                        JOIN auth_sessions s ON s.id = r.session_id
                        WHERE s.user_id = :userId
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM audit_logs
                        WHERE event_type = 'ACCOUNT_DELETION'
                          AND subject_id = :userId
                          AND outcome = 'SUCCEEDED'
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single()).isEqualTo(1L);

        ResponseEntity<JsonNode> login = login(accountNo, "correct horse battery staple");
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login.getBody().get("code").asText()).isEqualTo("AUTH_INVALID");

        JsonNode replacement = createUser("Replacement");
        assertThat(replacement.get("accountNo").asText()).isEqualTo(REPLACEMENT_NUMBER);
    }

    private JsonNode createUser(String displayName) throws Exception {
        ResponseEntity<JsonNode> response = http.postForEntity(
                "/api/v1/admin/users",
                new HttpEntity<>(
                        objectMapper.writeValueAsString(Map.of(
                                "displayName", displayName,
                                "password", "correct horse battery staple")),
                        adminHeaders()),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> login(String accountNo, String password) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        objectMapper.writeValueAsString(Map.of(
                                "accountNo", accountNo,
                                "password", password)),
                        headers),
                JsonNode.class);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        return headers;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedNumberGeneratorConfiguration {

        @Bean
        @Primary
        PublicNumberGenerator publicNumberGenerator() {
            String[] candidates = {RETIRED_NUMBER, RETIRED_NUMBER, REPLACEMENT_NUMBER};
            AtomicInteger next = new AtomicInteger();
            return new PublicNumberGenerator(() -> candidates[next.getAndIncrement()]);
        }
    }
}
