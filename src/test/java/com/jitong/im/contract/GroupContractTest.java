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
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class GroupContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void creates_immutable_owner_group_and_applies_visibility_to_exact_and_name_search() throws Exception {
        TestUser owner = createUser("Group owner");
        String token = login(owner.accountNo(), "group-creation");

        JsonNode publicGroup = createGroup(token, "Jitong Lounge", "A public place", "PUBLIC");
        assertThat(publicGroup.get("conversationId").asText()).isNotBlank();
        assertThat(publicGroup.get("groupNo").asText()).matches("[1-9][0-9]{10}");
        assertThat(publicGroup.get("visibility").asText()).isEqualTo("PUBLIC");
        assertThat(publicGroup.get("ownerUserId").asText()).isEqualTo(owner.userId().toString());
        assertThat(publicGroup.get("role").asText()).isEqualTo("OWNER");
        assertThat(publicGroup.get("memberCount").asInt()).isEqualTo(1);

        JsonNode unlistedGroup = createGroup(token, "Hidden Lounge", "Share by number", "UNLISTED");
        JsonNode privateGroup = createGroup(token, "Secret Lounge", "Invite only", "PRIVATE");

        assertThat(search(token, "Jitong Lounge").getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode nameSearch = search(token, "Jitong Lounge").getBody().get("groups");
        assertThat(nameSearch).hasSize(1);
        assertThat(nameSearch.get(0).get("name").asText()).isEqualTo("Jitong Lounge");

        assertThat(search(token, publicGroup.get("groupNo").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(search(token, unlistedGroup.get("groupNo").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(search(token, privateGroup.get("groupNo").asText()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode listed = exchange(HttpMethod.GET, "/api/v1/groups", token, null).getBody();
        assertThat(listed).hasSize(3);
        assertThat(listed.findValuesAsText("role")).containsOnly("OWNER");
    }

    @Test
    void group_search_returns_only_minimal_public_fields_and_private_numbers_are_uniformly_hidden()
            throws Exception {
        TestUser owner = createUser("Search owner");
        TestUser outsider = createUser("Search outsider");
        String ownerToken = login(owner.accountNo(), "search-owner");
        String outsiderToken = login(outsider.accountNo(), "search-outsider");

        JsonNode privateGroup = createGroup(ownerToken, "Private Lounge", "No discovery", "PRIVATE");
        ResponseEntity<JsonNode> exact = search(
                outsiderToken,
                privateGroup.get("groupNo").asText());
        assertThat(exact.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode publicGroup = createGroup(ownerToken, "Public Lounge", "Minimal result", "PUBLIC");
        JsonNode result = search(outsiderToken, publicGroup.get("groupNo").asText())
                .getBody().get("groups").get(0);
        assertThat(result.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "name",
                "avatarUrl",
                "description",
                "memberCount");
        assertThat(result.get("name").asText()).isEqualTo("Public Lounge");
        assertThat(result.get("description").asText()).isEqualTo("Minimal result");
        assertThat(result.get("memberCount").asInt()).isEqualTo(1);
        assertThat(result.get("conversationId")).isNull();
        assertThat(result.get("groupNo")).isNull();
        assertThat(result.get("ownerUserId")).isNull();
        assertThat(result.get("visibility")).isNull();

        assertThat(search(outsiderToken, "Minimal").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejects_invalid_visibility_and_blank_names_before_group_creation() throws Exception {
        TestUser owner = createUser("Validation owner");
        String token = login(owner.accountNo(), "validation-owner");

        ResponseEntity<JsonNode> invalid = exchange(
                HttpMethod.POST,
                "/api/v1/groups",
                token,
                Map.of("name", "Bad group", "visibility", "UNKNOWN"));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> blankName = exchange(
                HttpMethod.POST,
                "/api/v1/groups",
                token,
                Map.of("name", " ", "visibility", "PUBLIC"));
        assertThat(blankName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejects_the_101st_active_member_at_the_database_boundary() throws Exception {
        TestUser owner = createUser("Limit owner");
        String token = login(owner.accountNo(), "limit-owner");
        JsonNode group = createGroup(token, "Full group", "", "PRIVATE");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());

        for (int index = 0; index < 99; index++) {
            TestUser member = createUser("Limit member " + index);
            assertThat(addMember(token, conversationId, member.accountNo()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        TestUser rejected = createUser("Rejected member");
        ResponseEntity<JsonNode> response = addMember(token, conversationId, rejected.accountNo());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void invite_link_is_expiring_usage_limited_and_approval_gated() throws Exception {
        TestUser owner = createUser("Invite owner");
        TestUser applicant = createUser("Invite applicant");
        TestUser secondApplicant = createUser("Second applicant");
        String ownerToken = login(owner.accountNo(), "invite-owner");
        String applicantToken = login(applicant.accountNo(), "invite-applicant");
        String secondApplicantToken = login(secondApplicant.accountNo(), "invite-second-applicant");

        JsonNode group = createGroup(ownerToken, "Invite Lounge", "Approval required", "PRIVATE");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());
        JsonNode invite = exchange(
                HttpMethod.POST,
                "/api/v1/groups/" + conversationId + "/invites",
                ownerToken,
                Map.of("maxUses", 1, "expiresInSeconds", 600))
                .getBody();
        String deepLink = invite.get("deepLink").asText();
        assertThat(deepLink).startsWith("https://");
        assertThat(invite.get("qrPayload").asText()).isEqualTo(deepLink);
        String token = URI.create(deepLink).getQuery().substring("token=".length());

        ResponseEntity<JsonNode> resolved = exchange(
                HttpMethod.GET,
                "/api/v1/groups/invites/resolve?token=" + token,
                applicantToken,
                null);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody().get("conversationId").asText())
                .isEqualTo(conversationId.toString());

        ResponseEntity<JsonNode> firstRequest = exchange(
                HttpMethod.POST,
                "/api/v1/groups/" + conversationId + "/join-requests",
                applicantToken,
                Map.of("inviteToken", token));
        assertThat(firstRequest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstRequest.getBody().get("status").asText()).isEqualTo("PENDING");
        UUID requestId = UUID.fromString(firstRequest.getBody().get("requestId").asText());

        ResponseEntity<JsonNode> duplicateRequest = exchange(
                HttpMethod.POST,
                "/api/v1/groups/" + conversationId + "/join-requests",
                applicantToken,
                Map.of("inviteToken", token));
        assertThat(duplicateRequest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(duplicateRequest.getBody().get("requestId").asText())
                .isEqualTo(requestId.toString());

        assertThat(exchange(HttpMethod.GET, "/api/v1/groups", applicantToken, null)
                .getBody()).isEmpty();
        JsonNode queue = exchange(
                HttpMethod.GET,
                "/api/v1/groups/" + conversationId + "/join-requests",
                ownerToken,
                null).getBody();
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).get("userId").asText()).isEqualTo(applicant.userId().toString());

        ResponseEntity<JsonNode> approved = exchange(
                HttpMethod.POST,
                "/api/v1/groups/" + conversationId + "/join-requests/" + requestId + "/approve",
                ownerToken,
                null);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("status").asText()).isEqualTo("APPROVED");
        assertThat(exchange(HttpMethod.GET, "/api/v1/groups", applicantToken, null)
                .getBody()).hasSize(1);

        assertThat(exchange(
                HttpMethod.POST,
                "/api/v1/groups/" + conversationId + "/join-requests",
                secondApplicantToken,
                Map.of("inviteToken", token)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ordinary_removal_allows_reapplication_but_ban_blocks_every_entry_path() throws Exception {
        TestUser owner = createUser("Ban owner");
        TestUser member = createUser("Ban member");
        String ownerToken = login(owner.accountNo(), "ban-owner");
        String memberToken = login(member.accountNo(), "ban-member");

        JsonNode group = createGroup(ownerToken, "Governed Lounge", "", "PUBLIC");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());
        assertThat(addMember(ownerToken, conversationId, member.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(exchange(
                HttpMethod.DELETE,
                "/api/v1/groups/" + conversationId + "/members/" + member.userId(),
                ownerToken,
                null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(
                HttpMethod.POST,
                "/api/v1/groups/" + conversationId + "/join-requests",
                memberToken,
                null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(exchange(
                HttpMethod.POST,
                "/api/v1/groups/" + conversationId + "/bans/" + member.userId(),
                ownerToken,
                Map.of("reason", "abuse")).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(search(memberToken, group.get("groupNo").asText()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(addMember(ownerToken, conversationId, member.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(
                HttpMethod.POST,
                "/api/v1/groups/" + conversationId + "/join-requests",
                memberToken,
                null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void concurrent_join_requests_are_idempotent() throws Exception {
        TestUser owner = createUser("Concurrent owner");
        TestUser applicant = createUser("Concurrent applicant");
        String ownerToken = login(owner.accountNo(), "concurrent-owner");
        String applicantToken = login(applicant.accountNo(), "concurrent-applicant");
        JsonNode group = createGroup(ownerToken, "Concurrent Lounge", "", "PUBLIC");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ResponseEntity<JsonNode>>> calls = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> (Callable<ResponseEntity<JsonNode>>) () -> exchange(
                            HttpMethod.POST,
                            "/api/v1/groups/" + conversationId + "/join-requests",
                            applicantToken,
                            null))
                    .toList();
            List<Future<ResponseEntity<JsonNode>>> responses = executor.invokeAll(calls);
            List<ResponseEntity<JsonNode>> completed = responses.stream().map(this::get).toList();
            assertThat(completed).allSatisfy(response ->
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));
            assertThat(completed.stream()
                    .map(response -> response.getBody().get("requestId").asText())
                    .distinct()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private JsonNode createGroup(
            String token,
            String name,
            String description,
            String visibility
    ) throws Exception {
        return exchange(
                HttpMethod.POST,
                "/api/v1/groups",
                token,
                Map.of("name", name, "description", description, "visibility", visibility))
                .getBody();
    }

    private ResponseEntity<JsonNode> search(String token, String query) {
        return exchange(
                HttpMethod.GET,
                "/api/v1/groups/search?query=" + query.replace(" ", "%20"),
                token,
                null);
    }

    private ResponseEntity<JsonNode> addMember(
            String token,
            UUID conversationId,
            String accountNo
    ) {
        return exchange(
                HttpMethod.POST,
                "/api/v1/groups/" + conversationId + "/members",
                token,
                Map.of("accountNo", accountNo));
    }

    private ResponseEntity<JsonNode> get(Future<ResponseEntity<JsonNode>> response) {
        try {
            return response.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
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
                new HttpEntity<>(body == null ? null : json(body), headers),
                JsonNode.class);
    }

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record TestUser(UUID userId, String accountNo) {
    }
}
