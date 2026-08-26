package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.push.FcmDeliveryResult;
import com.jitong.im.push.FcmSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "jitong.push.enabled=true",
        "jitong.push.token-encryption-key=contract-test-push-key",
        "jitong.outbox.poll-interval=50"
})
class FcmFallbackContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private FcmSender fcmSender;

    @Test
    void sends_content_free_fcm_prompt_when_a_mobile_device_has_no_websocket() throws Exception {
        when(fcmSender.sendNewMessage(anyString())).thenReturn(FcmDeliveryResult.SENT);
        when(fcmSender.sendContactRequest(anyString())).thenReturn(FcmDeliveryResult.SENT);

        TestUser alice = createUser("FCM sender");
        TestUser bob = createUser("FCM receiver");
        String aliceToken = login(alice.accountNo(), "fcm-alice-installation");
        String bobToken = login(bob.accountNo(), "fcm-bob-installation");
        registerPushToken(bobToken, "old-bob-fcm-token", 1);
        registerPushToken(bobToken, "bob-fcm-token", 2);
        UUID conversationId = acceptContact(aliceToken, bob, bobToken);

        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST,
                "/api/v1/conversations/" + conversationId + "/messages",
                aliceToken,
                Map.of(
                        "clientMsgId", UUID.randomUUID(),
                        "text", "this text stays in the authoritative message history"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        verify(fcmSender, timeout(5_000)).sendNewMessage("bob-fcm-token");
    }

    @Test
    void sends_a_contact_request_fcm_prompt_when_a_mobile_device_has_no_websocket() throws Exception {
        when(fcmSender.sendContactRequest(anyString())).thenReturn(FcmDeliveryResult.SENT);

        TestUser alice = createUser("FCM request sender");
        TestUser bob = createUser("FCM request receiver");
        String aliceToken = login(alice.accountNo(), "fcm-request-alice-installation");
        String bobToken = login(bob.accountNo(), "fcm-request-bob-installation");
        registerPushToken(bobToken, "bob-request-fcm-token", 1);

        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests",
                aliceToken,
                Map.of("accountNo", bob.accountNo(), "verification", ""));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        verify(fcmSender, timeout(5_000)).sendContactRequest("bob-request-fcm-token");
    }

    private TestUser createUser(String displayName) throws Exception {
        HttpHeaders headers = jsonHeaders();
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        ResponseEntity<JsonNode> response = http.postForEntity(
                "/api/v1/admin/users",
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "displayName", displayName,
                        "password", "correct horse battery staple")), headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        return new TestUser(
                UUID.fromString(body.get("userId").asText()),
                body.get("accountNo").asText());
    }

    private String login(String accountNo, String installationId) throws Exception {
        ResponseEntity<JsonNode> response = http.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "accountNo", accountNo,
                        "password", "correct horse battery staple",
                        "deviceClass", "MOBILE",
                        "installationId", installationId)), jsonHeaders()),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("accessToken").asText();
    }

    private void registerPushToken(String accessToken, String token, long tokenVersion) throws Exception {
        ResponseEntity<Void> response = http.exchange(
                "/api/v1/devices/push-token",
                HttpMethod.POST,
                new HttpEntity<>(
                        objectMapper.writeValueAsString(Map.of(
                                "token", token,
                                "tokenVersion", tokenVersion)),
                        bearerJsonHeaders(accessToken)),
                Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private UUID acceptContact(
            String aliceToken,
            TestUser bob,
            String bobToken
    ) throws Exception {
        ResponseEntity<JsonNode> request = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests",
                aliceToken,
                Map.of("accountNo", bob.accountNo(), "verification", ""));
        UUID requestId = UUID.fromString(request.getBody().get("requestId").asText());
        ResponseEntity<JsonNode> accepted = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + requestId + "/accept",
                bobToken,
                null);
        return UUID.fromString(accepted.getBody().get("conversationId").asText());
    }

    private ResponseEntity<JsonNode> exchange(
            HttpMethod method,
            String path,
            String accessToken,
            Object body
    ) throws Exception {
        HttpEntity<String> entity = new HttpEntity<>(
                body == null ? null : objectMapper.writeValueAsString(body),
                bearerJsonHeaders(accessToken));
        return http.exchange(path, method, entity, JsonNode.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders bearerJsonHeaders(String accessToken) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private record TestUser(UUID userId, String accountNo) {
    }
}
