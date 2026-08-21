package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void uploads_replaces_removes_and_enforces_c2c_visibility_with_versioned_cache_invalidation()
            throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        TestUser eve = createUser("Eve");
        String aliceToken = login(alice.accountNo(), "avatar-alice");
        String bobToken = login(bob.accountNo(), "avatar-bob");
        String eveToken = login(eve.accountNo(), "avatar-eve");
        acceptContact(aliceToken, bobToken, bob.accountNo());

        ResponseEntity<JsonNode> first = replaceAvatar(
                aliceToken,
                UUID.randomUUID(),
                png(400, 200),
                100,
                0,
                200,
                200);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("purpose").asText()).isEqualTo("AVATAR");
        assertThat(first.getBody().get("state").asText()).isEqualTo("BOUND");
        assertThat(first.getBody().get("contentType").asText()).isEqualTo("image/webp");
        assertThat(first.getBody().get("width").asInt()).isEqualTo(512);
        assertThat(first.getBody().get("height").asInt()).isEqualTo(512);
        assertThat(first.getBody().get("avatarVersion").asLong()).isEqualTo(1);
        assertThat(first.getBody().get("thumbnailUrl").asText()).contains("avatarVersion=1");

        ResponseEntity<byte[]> ownThumb = downloadAvatar(aliceToken, alice.userId(), 1);
        assertThat(ownThumb.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownThumb.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("image/webp"));
        BufferedImage thumbnail = ImageIO.read(new java.io.ByteArrayInputStream(ownThumb.getBody()));
        assertThat(thumbnail.getWidth()).isEqualTo(96);
        assertThat(thumbnail.getHeight()).isEqualTo(96);

        assertThat(downloadAvatar(bobToken, alice.userId(), 1).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> eveDenied = exchange(
                HttpMethod.GET,
                "/api/v1/users/" + alice.userId() + "/avatar?variant=thumb",
                eveToken,
                null);
        assertThat(eveDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> replacement = replaceAvatar(
                aliceToken,
                UUID.randomUUID(),
                png(200, 200),
                null,
                null,
                null,
                null);
        assertThat(replacement.getBody().get("avatarVersion").asLong()).isEqualTo(2);
        assertThat(downloadAvatar(aliceToken, alice.userId(), 1).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
        assertThat(downloadAvatar(aliceToken, alice.userId(), 2).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> removed = exchangeVoid(
                HttpMethod.DELETE,
                "/api/v1/users/me/avatar",
                aliceToken);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(downloadAvatar(aliceToken, alice.userId(), 2).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
    }

    private ResponseEntity<JsonNode> replaceAvatar(
            String token,
            UUID uploadId,
            byte[] content,
            Integer cropX,
            Integer cropY,
            Integer cropWidth,
            Integer cropHeight
    ) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new NamedByteArrayResource(content, "avatar.png"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        String path = "/api/v1/users/me/avatar?uploadId=" + uploadId;
        if (cropX != null) {
            path += "&cropX=" + cropX
                    + "&cropY=" + cropY
                    + "&cropWidth=" + cropWidth
                    + "&cropHeight=" + cropHeight;
        }
        return http.exchange(
                path,
                HttpMethod.PUT,
                new HttpEntity<>(parts, headers),
                JsonNode.class);
    }

    private ResponseEntity<byte[]> downloadAvatar(String token, UUID userId, long version) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(
                "/api/v1/users/" + userId
                        + "/avatar?variant=thumb&avatarVersion=" + version,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
    }

    private UUID acceptContact(String requesterToken, String recipientToken, String recipientAccountNo)
            throws Exception {
        JsonNode request = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests",
                requesterToken,
                Map.of("accountNo", recipientAccountNo, "verification", "")).getBody();
        return UUID.fromString(exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + request.get("requestId").asText() + "/accept",
                recipientToken,
                null).getBody().get("conversationId").asText());
    }

    private TestUser createUser(String displayName) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        ResponseEntity<String> response = http.postForEntity(
                "/api/v1/admin/users",
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "displayName", displayName + UUID.randomUUID(),
                        "password", "correct horse battery staple")), headers),
                String.class);
        JsonNode body = objectMapper.readTree(response.getBody());
        return new TestUser(
                UUID.fromString(body.get("userId").asText()),
                body.get("accountNo").asText());
    }

    private String login(String accountNo, String installationId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "accountNo", accountNo,
                        "password", "correct horse battery staple",
                        "installationId", installationId)), headers),
                String.class);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private ResponseEntity<JsonNode> exchange(
            HttpMethod method,
            String path,
            String token,
            Object body
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(
                path,
                method,
                new HttpEntity<>(body == null ? null : writeJson(body), headers),
                JsonNode.class);
    }

    private ResponseEntity<Void> exchangeVoid(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, method, new HttpEntity<>(headers), Void.class);
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private record TestUser(UUID userId, String accountNo) {
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] content, String filename) {
            super(content);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
