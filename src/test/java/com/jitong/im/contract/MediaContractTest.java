package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MediaContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    @Test
    void normalizes_image_uploads_keeps_media_private_and_allows_conversation_participants_to_download()
            throws Exception {
        TestUser alice = createUser("Alice");
        TestUser bob = createUser("Bob");
        TestUser eve = createUser("Eve");
        String aliceToken = login(alice.accountNo(), "media-alice");
        String bobToken = login(bob.accountNo(), "media-bob");
        String eveToken = login(eve.accountNo(), "media-eve");
        UUID conversationId = acceptContact(aliceToken, bobToken, bob.accountNo());

        byte[] png = png(480, 240);
        UUID uploadId = UUID.randomUUID();
        ResponseEntity<JsonNode> uploaded = upload(aliceToken, uploadId, png, "spoofed.txt");

        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode media = uploaded.getBody();
        assertThat(media.get("purpose").asText()).isEqualTo("MESSAGE_IMAGE");
        assertThat(media.get("state").asText()).isEqualTo("TEMP");
        assertThat(media.get("contentType").asText()).isEqualTo("image/jpeg");
        assertThat(media.get("width").asInt()).isEqualTo(480);
        assertThat(media.get("height").asInt()).isEqualTo(240);

        UUID mediaId = UUID.fromString(media.get("mediaId").asText());
        ResponseEntity<byte[]> ownerDownload = download(aliceToken, mediaId, "full");
        assertThat(ownerDownload.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerDownload.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(ownerDownload.getBody()))).isNotNull();

        ResponseEntity<JsonNode> sent = exchange(
                HttpMethod.POST,
                "/api/v1/conversations/" + conversationId + "/messages",
                aliceToken,
                Map.of(
                        "clientMsgId", UUID.randomUUID(),
                        "type", "IMAGE",
                        "mediaId", mediaId));
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().get("type").asText()).isEqualTo("IMAGE");
        assertThat(sent.getBody().get("text").isNull()).isTrue();
        assertThat(sent.getBody().get("mediaId").asText()).isEqualTo(mediaId.toString());

        ResponseEntity<byte[]> peerDownload = download(bobToken, mediaId, "thumb");
        assertThat(peerDownload.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(peerDownload.getBody()).isNotEmpty();
        BufferedImage thumbnail = ImageIO.read(
                new java.io.ByteArrayInputStream(peerDownload.getBody()));
        assertThat(thumbnail.getWidth()).isEqualTo(320);
        assertThat(thumbnail.getHeight()).isEqualTo(160);

        ResponseEntity<JsonNode> forbidden = exchange(
                HttpMethod.GET,
                "/api/v1/media/" + mediaId,
                eveToken,
                null);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody().get("code").asText()).isEqualTo("MEDIA_FORBIDDEN");
    }

    @Test
    void rejects_content_that_is_not_a_decodable_image_even_when_the_part_type_is_spoofed()
            throws Exception {
        TestUser alice = createUser("Alice");
        String token = login(alice.accountNo(), "media-invalid");

        ResponseEntity<JsonNode> response = upload(
                token,
                UUID.randomUUID(),
                "not an image".getBytes(),
                "photo.jpg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("MEDIA_INVALID");
    }

    @Test
    void rejects_uploads_that_exceed_the_multipart_size_limit() throws Exception {
        TestUser alice = createUser("Alice");
        String token = login(alice.accountNo(), "media-too-large");

        ResponseEntity<JsonNode> response = upload(
                token,
                UUID.randomUUID(),
                new byte[10 * 1024 * 1024 + 1],
                "too-large.jpg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("code").asText()).isEqualTo("MEDIA_TOO_LARGE");
    }

    @Test
    void rejects_images_that_exceed_the_decode_pixel_limit() throws Exception {
        TestUser alice = createUser("Alice");
        String token = login(alice.accountNo(), "media-too-many-pixels");

        BufferedImage source = new BufferedImage(5000, 5000, BufferedImage.TYPE_BYTE_BINARY);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);

        ResponseEntity<JsonNode> response = upload(
                token,
                UUID.randomUUID(),
                encoded.toByteArray(),
                "too-many-pixels.png");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("MEDIA_DIMENSIONS_TOO_LARGE");
    }

    private ResponseEntity<JsonNode> upload(
            String token,
            UUID uploadId,
            byte[] content,
            String filename
    ) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new NamedByteArrayResource(content, filename));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(
                "/api/v1/media/images?uploadId=" + uploadId,
                HttpMethod.POST,
                new HttpEntity<>(parts, headers),
                JsonNode.class);
    }

    private ResponseEntity<byte[]> download(String token, UUID mediaId, String variant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(
                "/api/v1/media/" + mediaId + "?variant=" + variant,
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
        headers.set("X-Admin-Api-Key", "contract-test-admin-key");
        ResponseEntity<String> response = http.postForEntity(
                "/api/v1/admin/users",
                new HttpEntity<>(
                        objectMapper.writeValueAsString(Map.of(
                                "displayName", displayName + UUID.randomUUID(),
                                "password", "correct horse battery staple")),
                        headers),
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
                new HttpEntity<>(
                        objectMapper.writeValueAsString(Map.of(
                                "accountNo", accountNo,
                                "password", "correct horse battery staple",
                                "installationId", installationId)),
                        headers),
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

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, new Color(x % 255, y % 255, 120).getRGB());
            }
        }
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
