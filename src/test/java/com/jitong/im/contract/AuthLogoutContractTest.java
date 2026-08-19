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

import static org.assertj.core.api.Assertions.assertThat;

class AuthLogoutContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void logout_revokes_only_the_current_session_and_its_refresh_token() throws Exception {
        JsonNode created = createUser();
        String accountNo = created.get("accountNo").asText();
        JsonNode login = login(accountNo);

        ResponseEntity<Void> logout = http.exchange(
                "/api/v1/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(login.get("accessToken").asText())),
                Void.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(validate(login.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(login.get("refreshToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private JsonNode createUser() throws Exception {
        HttpHeaders headers = jsonHeaders();
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        ResponseEntity<JsonNode> response = http.postForEntity(
                "/api/v1/admin/users",
                new HttpEntity<>(json(Map.of(
                        "displayName", "Logout",
                        "password", "correct horse battery staple")), headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode login(String accountNo) throws Exception {
        ResponseEntity<JsonNode> response = http.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(json(Map.of(
                        "accountNo", accountNo,
                        "password", "correct horse battery staple")), jsonHeaders()),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Void> validate(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return http.exchange(
                "/api/v1/auth/validate",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Void.class);
    }

    private ResponseEntity<Void> refresh(String refreshToken) throws Exception {
        return http.exchange(
                "/api/v1/auth/refresh",
                HttpMethod.POST,
                new HttpEntity<>(json(Map.of("refreshToken", refreshToken)), jsonHeaders()),
                Void.class);
    }

    private HttpHeaders bearerHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
