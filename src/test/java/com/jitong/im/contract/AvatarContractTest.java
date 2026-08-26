package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    @Autowired
    private JdbcClient jdbc;

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

    @Test
    void updates_the_current_users_display_name_and_returns_the_new_profile() throws Exception {
        TestUser alice = createUser("Alice");
        String aliceToken = login(alice.accountNo(), "profile-alice");

        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.PUT,
                "/api/v1/users/me/profile",
                aliceToken,
                Map.of("displayName", "Alice updated"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("userId").asText()).isEqualTo(alice.userId().toString());
        assertThat(response.getBody().get("displayName").asText()).isEqualTo("Alice updated");

        ResponseEntity<JsonNode> profile = exchange(
                HttpMethod.GET,
                "/api/v1/users/" + alice.userId() + "/profile",
                aliceToken,
                null);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody().get("displayName").asText()).isEqualTo("Alice updated");
    }

    @Test
    void rejects_a_blank_display_name_update() throws Exception {
        TestUser alice = createUser("Alice");
        String aliceToken = login(alice.accountNo(), "profile-alice-blank");

        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.PUT,
                "/api/v1/users/me/profile",
                aliceToken,
                Map.of("displayName", "   "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void group_avatar_is_independent_and_visible_only_to_active_members() throws Exception {
        TestUser owner = createUser("Group owner");
        TestUser member = createUser("Group member");
        TestUser outsider = createUser("Group outsider");
        String ownerToken = login(owner.accountNo(), "group-owner");
        String memberToken = login(member.accountNo(), "group-member");
        String outsiderToken = login(outsider.accountNo(), "group-outsider");
        UUID conversationId = UUID.randomUUID();

        jdbc.sql("INSERT INTO conversations (id, type, status) VALUES (:id, 'GROUP', 'ACTIVE')")
                .param("id", conversationId)
                .update();
        jdbc.sql("""
                        INSERT INTO groups (
                            conversation_id, group_no, name, owner_user_id, visibility)
                        VALUES (:conversationId, :groupNo, 'Avatar group', :ownerId, 'PRIVATE')
                        """)
                .param("conversationId", conversationId)
                .param("groupNo", "9" + String.format("%010d", conversationId.getLeastSignificantBits()
                        & 0x7fffffffffffffffL).substring(0, 10))
                .param("ownerId", owner.userId())
                .update();
        insertGroupMember(conversationId, owner.userId(), "OWNER");
        insertGroupMember(conversationId, member.userId(), "MEMBER");

        ResponseEntity<JsonNode> upload = replaceGroupAvatar(
                ownerToken, conversationId, UUID.randomUUID(), png(320, 180));
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upload.getBody().get("avatarVersion").asLong()).isEqualTo(1);

        assertThat(downloadGroupAvatar(memberToken, conversationId, 1).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(downloadGroupAvatar(outsiderToken, conversationId, 1).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> replacement = replaceGroupAvatar(
                ownerToken, conversationId, UUID.randomUUID(), png(180, 180));
        assertThat(replacement.getBody().get("avatarVersion").asLong()).isEqualTo(2);
        assertThat(downloadGroupAvatar(memberToken, conversationId, 1).getStatusCode())
                .isEqualTo(HttpStatus.GONE);

        assertThat(exchangeVoid(
                HttpMethod.DELETE,
                "/api/v1/groups/" + conversationId + "/avatar",
                ownerToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(downloadGroupAvatar(memberToken, conversationId, 2).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
    }

    private void insertGroupMember(UUID conversationId, UUID userId, String role) {
        jdbc.sql("""
                        INSERT INTO conversation_members (conversation_id, user_id, role)
                        VALUES (:conversationId, :userId, :role)
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .param("role", role)
                .update();
    }

    private ResponseEntity<JsonNode> replaceGroupAvatar(
            String token,
            UUID conversationId,
            UUID uploadId,
            byte[] content
    ) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new NamedByteArrayResource(content, "group-avatar.png"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(
                "/api/v1/groups/" + conversationId + "/avatar?uploadId=" + uploadId,
                HttpMethod.PUT,
                new HttpEntity<>(parts, headers),
                JsonNode.class);
    }

    private ResponseEntity<byte[]> downloadGroupAvatar(
            String token,
            UUID conversationId,
            long version
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(
                "/api/v1/groups/" + conversationId
                        + "/avatar?variant=thumb&avatarVersion=" + version,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
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
