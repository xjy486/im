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
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void password_change_rotates_the_current_device_and_revokes_other_devices() throws Exception {
        JsonNode user = createUser("Password change");
        String accountNo = user.get("accountNo").asText();
        JsonNode mobile = login(accountNo, "password-change-mobile", "MOBILE", "correct horse battery staple");
        JsonNode pc = login(accountNo, "password-change-pc", "PC", "correct horse battery staple");

        ResponseEntity<JsonNode> changed = changePassword(
                mobile.get("accessToken").asText(),
                "correct horse battery staple",
                "a new password for testing");

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode replacement = changed.getBody();
        assertThat(replacement.get("accessToken").asText())
                .isNotEqualTo(mobile.get("accessToken").asText());
        assertThat(replacement.get("refreshToken").asText())
                .isNotEqualTo(mobile.get("refreshToken").asText());
        assertThat(replacement.get("passwordMustChange").asBoolean()).isFalse();
        assertThat(protectedResponse(mobile.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(protectedResponse(pc.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(protectedResponse(replacement.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(loginResponse(
                accountNo,
                "password-change-mobile",
                "MOBILE",
                "correct horse battery staple").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(
                accountNo,
                "password-change-mobile",
                "MOBILE",
                "a new password for testing").get("accessToken").asText())
                .isNotBlank();
    }

    @Test
    void incorrect_current_password_does_not_change_password_or_revoke_sessions() throws Exception {
        JsonNode user = createUser("Incorrect password");
        String accountNo = user.get("accountNo").asText();
        JsonNode mobile = login(accountNo, "incorrect-mobile", "MOBILE", "correct horse battery staple");
        JsonNode pc = login(accountNo, "incorrect-pc", "PC", "correct horse battery staple");

        ResponseEntity<JsonNode> changed = changePassword(
                mobile.get("accessToken").asText(),
                "wrong current password",
                "a new password for testing");

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(protectedResponse(mobile.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(protectedResponse(pc.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(loginResponse(
                accountNo,
                "incorrect-new-password",
                "MOBILE",
                "a new password for testing").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void admin_reset_returns_one_time_temporary_password_and_forces_password_change() throws Exception {
        JsonNode user = createUser("Admin reset");
        String userId = user.get("userId").asText();
        String accountNo = user.get("accountNo").asText();
        JsonNode mobile = login(accountNo, "reset-mobile", "MOBILE", "correct horse battery staple");
        JsonNode pc = login(accountNo, "reset-pc", "PC", "correct horse battery staple");

        ResponseEntity<JsonNode> reset = resetPassword(userId);

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        String temporaryPassword = reset.getBody().get("temporaryPassword").asText();
        assertThat(temporaryPassword).isNotBlank();
        assertThat(reset.getBody().get("passwordMustChange").asBoolean()).isTrue();
        assertThat(protectedResponse(mobile.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(protectedResponse(pc.get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(loginResponse(
                accountNo,
                "reset-old-password",
                "PC",
                "correct horse battery staple").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode temporaryLogin = login(
                accountNo,
                "reset-mobile",
                "MOBILE",
                temporaryPassword);
        assertThat(temporaryLogin.get("passwordMustChange").asBoolean()).isTrue();
        ResponseEntity<JsonNode> blocked = protectedResponse(
                temporaryLogin.get("accessToken").asText());
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blocked.getBody().get("code").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");

        ResponseEntity<JsonNode> changed = changePassword(
                temporaryLogin.get("accessToken").asText(),
                temporaryPassword,
                "restored permanent password");
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().get("passwordMustChange").asBoolean()).isFalse();
        assertThat(protectedResponse(changed.getBody().get("accessToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(loginResponse(
                accountNo,
                "reset-second-login",
                "PC",
                temporaryPassword).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
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

    private JsonNode login(
            String accountNo,
            String installationId,
            String deviceClass,
            String password
    ) throws Exception {
        ResponseEntity<JsonNode> response = loginResponse(
                accountNo,
                installationId,
                deviceClass,
                password);
        assertThat(response.getStatusCode())
                .withFailMessage("login response: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> loginResponse(
            String accountNo,
            String installationId,
            String deviceClass,
            String password
    ) throws Exception {
        return http.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "accountNo", accountNo,
                        "password", password,
                        "deviceClass", deviceClass,
                        "installationId", installationId)), jsonHeaders()),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> changePassword(
            String accessToken,
            String currentPassword,
            String newPassword
    ) throws Exception {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(accessToken);
        Map<String, Object> body = new HashMap<>();
        body.put("currentPassword", currentPassword);
        body.put("newPassword", newPassword);
        return http.exchange(
                "/api/v1/auth/password/change",
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> resetPassword(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        return http.exchange(
                "/api/v1/admin/users/" + userId + "/password-reset",
                HttpMethod.POST,
                new HttpEntity<>(headers),
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
