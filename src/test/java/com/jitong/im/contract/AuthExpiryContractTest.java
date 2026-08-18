package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "jitong.auth.access-token-lifetime=1ms")
class AuthExpiryContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void expired_access_tokens_are_rejected_by_protected_api() throws Exception {
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);
        adminHeaders.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        ResponseEntity<JsonNode> created = http.exchange(
                "/api/v1/admin/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        objectMapper.writeValueAsString(Map.of(
                                "displayName", "Expiring",
                                "password", "correct horse battery staple")),
                        adminHeaders),
                JsonNode.class);
        String accountNo = created.getBody().get("accountNo").asText();

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> login = http.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        objectMapper.writeValueAsString(Map.of(
                                "accountNo", accountNo,
                                "password", "correct horse battery staple")),
                        loginHeaders),
                JsonNode.class);
        String accessToken = login.getBody().get("accessToken").asText();

        Thread.sleep(20);

        HttpHeaders accessHeaders = new HttpHeaders();
        accessHeaders.setBearerAuth(accessToken);
        ResponseEntity<JsonNode> protectedResponse = http.exchange(
                "/api/v1/protected/test",
                HttpMethod.GET,
                new HttpEntity<>(accessHeaders),
                JsonNode.class);

        assertThat(protectedResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(protectedResponse.getBody().get("code").asText()).isEqualTo("TOKEN_EXPIRED");
    }
}
