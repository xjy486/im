package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class AuthConcurrencyContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void concurrent_preset_user_creation_returns_unique_valid_account_numbers() throws Exception {
        int requestCount = 32;
        long usersBefore = jdbc.sql("SELECT COUNT(*) FROM users")
                .query(Long.class)
                .single();
        long identifiersBefore = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM public_identifiers
                        WHERE entity_type = 'USER'
                        """)
                .query(Long.class)
                .single();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ResponseEntity<JsonNode>>> requests = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                int requestNumber = index;
                requests.add(() -> createUser("Concurrent-" + requestNumber));
            }

            List<ResponseEntity<JsonNode>> responses = executor.invokeAll(requests).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertThat(responses).allSatisfy(response ->
                    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue());

            Set<String> accountNumbers = responses.stream()
                    .map(response -> response.getBody().get("accountNo").asText())
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(accountNumbers).hasSize(requestCount);
            assertThat(accountNumbers)
                    .allMatch(accountNo -> accountNo.matches("[1-9][0-9]{10}"));
            assertThat(accountNumbers)
                    .allMatch(accountNo -> {
                        try {
                            return com.jitong.im.auth.PublicNumber.isValid(accountNo);
                        } catch (RuntimeException exception) {
                            return false;
                        }
                    });
            assertThat(jdbc.sql("SELECT COUNT(*) FROM users")
                    .query(Long.class)
                    .single()).isEqualTo(usersBefore + requestCount);
            assertThat(jdbc.sql("""
                            SELECT COUNT(*)
                            FROM public_identifiers
                            WHERE entity_type = 'USER'
                            """)
                    .query(Long.class)
                    .single()).isEqualTo(identifiersBefore + requestCount);
            assertThat(jdbc.sql("""
                            SELECT COUNT(*)
                            FROM users u
                            JOIN public_identifiers p
                              ON p.entity_id = u.id
                             AND p.entity_type = 'USER'
                            """)
                    .query(Long.class)
                    .single()).isEqualTo(usersBefore + requestCount);
        } finally {
            executor.shutdownNow();
        }
    }

    private ResponseEntity<JsonNode> createUser(String displayName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        try {
            return http.postForEntity(
                    "/api/v1/admin/users",
                    new HttpEntity<>(
                            objectMapper.writeValueAsString(Map.of(
                                    "displayName", displayName,
                                    "password", "correct horse battery staple")),
                            headers),
                    JsonNode.class);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
