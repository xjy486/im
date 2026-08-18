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
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthDeviceContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void one_mobile_and_one_pc_can_be_active_but_same_class_replacement_requires_confirmation() throws Exception {
        JsonNode user = createUser("Device slots");
        UUID userId = UUID.fromString(user.get("userId").asText());
        String accountNo = user.get("accountNo").asText();

        JsonNode mobile = login(accountNo, "mobile-installation-1", "MOBILE");
        JsonNode pc = login(accountNo, "pc-installation-1", "PC");

        ResponseEntity<JsonNode> replacementRequired = loginResponse(
                accountNo,
                "mobile-installation-2",
                "MOBILE");
        assertThat(replacementRequired.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(replacementRequired.getBody().get("code").asText())
                .isEqualTo("DEVICE_REPLACEMENT_REQUIRED");
        String challenge = replacementRequired.getBody().get("replacementChallenge").asText();
        assertThat(challenge).isNotBlank();

        assertThat(protectedResponse(mobile.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(protectedResponse(pc.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode replacement = confirmReplacement(challenge);
        assertThat(replacement.get("deviceClass").asText()).isEqualTo("MOBILE");
        assertThat(protectedResponse(replacement.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(protectedResponse(mobile.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(protectedResponse(pc.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> repeatedConfirmation = confirmReplacementResponse(challenge);
        assertThat(repeatedConfirmation.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

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
                        FROM devices
                        WHERE user_id = :userId
                          AND device_class = 'PC'
                          AND trust_state = 'ACTIVE'
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    @Test
    void logging_in_again_from_the_same_installation_reuses_the_device_identity() throws Exception {
        JsonNode user = createUser("Same installation");
        UUID userId = UUID.fromString(user.get("userId").asText());
        String accountNo = user.get("accountNo").asText();

        login(accountNo, "same-installation", "MOBILE");
        login(accountNo, "same-installation", "MOBILE");

        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM devices
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    @Test
    void refresh_tokens_rotate_and_replay_untrusts_only_the_affected_device() throws Exception {
        JsonNode user = createUser("Refresh rotation");
        String accountNo = user.get("accountNo").asText();

        JsonNode mobile = login(accountNo, "mobile-installation", "MOBILE");
        JsonNode pc = login(accountNo, "pc-installation", "PC");

        JsonNode rotated = refresh(pc.get("refreshToken").asText());
        assertThat(rotated.get("refreshToken").asText())
                .isNotEqualTo(pc.get("refreshToken").asText());
        assertThat(rotated.get("accessToken").asText())
                .isNotEqualTo(pc.get("accessToken").asText());
        assertThat(protectedResponse(rotated.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> replay = refreshResponse(pc.get("refreshToken").asText());
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody().get("code").asText()).isEqualTo("AUTH_INVALID");
        assertThat(protectedResponse(rotated.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(protectedResponse(mobile.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private JsonNode createUser(String displayName) throws Exception {
        HttpHeaders headers = jsonHeaders();
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        ResponseEntity<JsonNode> response = http.postForEntity(
                "/api/v1/admin/users",
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "displayName", displayName,
                        "password", "correct horse battery staple")), headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode login(String accountNo, String installationId, String deviceClass) throws Exception {
        ResponseEntity<JsonNode> response = loginResponse(accountNo, installationId, deviceClass);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> loginResponse(
            String accountNo,
            String installationId,
            String deviceClass
    ) throws Exception {
        return http.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "accountNo", accountNo,
                        "password", "correct horse battery staple",
                        "deviceClass", deviceClass,
                        "installationId", installationId)), jsonHeaders()),
                JsonNode.class);
    }

    private JsonNode confirmReplacement(String challenge) throws Exception {
        ResponseEntity<JsonNode> response = confirmReplacementResponse(challenge);
        assertThat(response.getStatusCode())
                .withFailMessage("replacement response: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> confirmReplacementResponse(String challenge) throws Exception {
        return http.exchange(
                "/api/v1/auth/device-replacement/confirm",
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "replacementChallenge", challenge)), jsonHeaders()),
                JsonNode.class);
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        ResponseEntity<JsonNode> response = refreshResponse(refreshToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> refreshResponse(String refreshToken) throws Exception {
        return http.exchange(
                "/api/v1/auth/refresh",
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "refreshToken", refreshToken)), jsonHeaders()),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> protectedResponse(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return http.exchange(
                "/api/v1/protected/test",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
