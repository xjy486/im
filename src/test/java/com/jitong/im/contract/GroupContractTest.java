package com.jitong.im.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

class GroupContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

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
    void group_number_join_requests_and_member_listing_are_available_to_the_desktop_flow()
            throws Exception {
        TestUser owner = createUser("Desktop group owner");
        TestUser applicant = createUser("Desktop group applicant");
        String ownerToken = login(owner.accountNo(), "desktop-group-owner");
        String applicantToken = login(applicant.accountNo(), "desktop-group-applicant");

        JsonNode group = createGroup(ownerToken, "Desktop Lounge", "", "PUBLIC");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());

        ResponseEntity<JsonNode> request = exchange(
                HttpMethod.POST,
                "/api/v1/groups/join-requests/by-group-no",
                applicantToken,
                Map.of("groupNo", group.get("groupNo").asText()));
        assertThat(request.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request.getBody().get("status").asText()).isEqualTo("PENDING");

        JsonNode mine = exchange(
                HttpMethod.GET,
                "/api/v1/groups/join-requests/mine",
                applicantToken,
                null).getBody();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("groupNo").asText())
                .isEqualTo(group.get("groupNo").asText());

        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/groups/" + conversationId + "/members",
                ownerToken,
                null).getBody()).hasSize(1);

        assertThat(exchange(
                HttpMethod.POST,
                "/api/v1/groups/" + conversationId + "/join-requests/" +
                        request.getBody().get("requestId").asText() + "/approve",
                ownerToken,
                null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/groups/" + conversationId + "/members",
                applicantToken,
                null).getBody()).hasSize(2);
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
    void owner_only_role_changes_and_owner_transfer_are_visible_in_the_shared_system_timeline()
            throws Exception {
        TestUser owner = createUser("Role owner");
        TestUser administrator = createUser("Role administrator");
        TestUser member = createUser("Role member");
        String ownerToken = login(owner.accountNo(), "role-owner");
        String administratorToken = login(administrator.accountNo(), "role-administrator");
        String memberToken = login(member.accountNo(), "role-member");

        JsonNode group = createGroup(ownerToken, "Role Lounge", "", "PRIVATE");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());
        assertThat(addMember(ownerToken, conversationId, administrator.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(addMember(ownerToken, conversationId, member.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(changeRole(
                administratorToken,
                conversationId,
                member.userId(),
                "ADMIN").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(changeRole(
                ownerToken,
                conversationId,
                member.userId(),
                "ADMIN").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changeRole(
                administratorToken,
                conversationId,
                member.userId(),
                "MEMBER").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode transferred = exchange(
                HttpMethod.POST,
                "/api/v1/groups/" + conversationId + "/owner-transfer",
                ownerToken,
                Map.of("userId", administrator.userId())).getBody();
        assertThat(transferred.get("previousOwnerUserId").asText())
                .isEqualTo(owner.userId().toString());
        assertThat(transferred.get("ownerUserId").asText())
                .isEqualTo(administrator.userId().toString());

        assertThat(exchange(HttpMethod.GET, "/api/v1/groups", ownerToken, null)
                .getBody().get(0).get("role").asText()).isEqualTo("ADMIN");
        assertThat(exchange(HttpMethod.GET, "/api/v1/groups", administratorToken, null)
                .getBody().get(0).get("role").asText()).isEqualTo("OWNER");

        JsonNode ownerHistory = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/messages?afterSeq=0&limit=200",
                ownerToken,
                null).getBody();
        JsonNode administratorHistory = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/messages?afterSeq=0&limit=200",
                administratorToken,
                null).getBody();
        assertThat(systemSequences(ownerHistory))
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(systemSequences(administratorHistory))
                .containsExactly(2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(ownerHistory.get("messages"))
                .extracting(node -> node.get("systemEventType").asText())
                .containsExactly(
                        "GROUP_CREATED",
                        "MEMBER_JOINED",
                        "MEMBER_JOINED",
                        "ROLE_CHANGED",
                        "ROLE_CHANGED",
                        "ROLE_CHANGED",
                        "AI_POLICY_CHANGED");
        assertThat(memberToken).isNotBlank();
    }

    @Test
    void admins_can_update_group_profile_and_profile_change_is_a_system_item()
            throws Exception {
        TestUser owner = createUser("Profile owner");
        TestUser administrator = createUser("Profile administrator");
        String ownerToken = login(owner.accountNo(), "profile-owner");
        String administratorToken = login(administrator.accountNo(), "profile-administrator");

        JsonNode group = createGroup(ownerToken, "Old name", "Old description", "PRIVATE");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());
        assertThat(addMember(ownerToken, conversationId, administrator.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(changeRole(
                ownerToken,
                conversationId,
                administrator.userId(),
                "ADMIN").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> updated = exchange(
                HttpMethod.PUT,
                "/api/v1/groups/" + conversationId + "/profile",
                administratorToken,
                Map.of(
                        "name", "New name",
                        "description", "New description",
                        "visibility", "UNLISTED"));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("name").asText()).isEqualTo("New name");
        assertThat(updated.getBody().get("visibility").asText()).isEqualTo("UNLISTED");

        JsonNode history = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/messages?afterSeq=0&limit=200",
                ownerToken,
                null).getBody();
        assertThat(history.get("messages"))
                .extracting(node -> node.get("systemEventType").asText())
                .contains("GROUP_PROFILE_UPDATED");
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

    @Test
    void group_messages_enforce_membership_boundaries_and_media_access() throws Exception {
        TestUser owner = createUser("Message owner");
        TestUser member = createUser("Message member");
        String ownerToken = login(owner.accountNo(), "group-message-owner");
        String memberToken = login(member.accountNo(), "group-message-member");

        JsonNode group = createGroup(ownerToken, "Message Lounge", "", "PRIVATE");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());
        postMessage(ownerToken, conversationId, "before join");
        assertThat(addMember(ownerToken, conversationId, member.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode afterJoin = postMessage(ownerToken, conversationId, "after join");
        JsonNode memberMessage = postMessage(memberToken, conversationId, "member message");
        assertThat(afterJoin.get("conversationSeq").asLong()).isEqualTo(4);
        assertThat(memberMessage.get("conversationSeq").asLong()).isEqualTo(5);

        JsonNode memberHistory = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/messages?afterSeq=0&limit=200",
                memberToken,
                null).getBody();
        assertThat(memberHistory.get("messages"))
                .extracting(node -> node.get("conversationSeq").asLong())
                .containsExactly(3L, 4L, 5L);

        JsonNode ownerHistory = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/messages?afterSeq=0&limit=200",
                ownerToken,
                null).getBody();
        assertThat(ownerHistory.get("messages"))
                .extracting(node -> node.get("conversationSeq").asLong())
                .containsExactly(1L, 2L, 3L, 4L, 5L);

        UUID mediaId = UUID.fromString(uploadImage(ownerToken).get("mediaId").asText());
        JsonNode image = exchange(
                HttpMethod.POST,
                "/api/v1/conversations/" + conversationId + "/messages",
                ownerToken,
                Map.of(
                        "clientMsgId", UUID.randomUUID(),
                        "type", "IMAGE",
                        "mediaId", mediaId))
                .getBody();
        assertThat(image.get("type").asText()).isEqualTo("IMAGE");
        assertThat(downloadMedia(memberToken, mediaId).getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode syncBeforeRemoval = exchange(
                HttpMethod.GET,
                "/api/v1/sync?after=0&until=0",
                memberToken,
                null).getBody();
        long beforeRemoval = syncBeforeRemoval.get("highWatermark").asLong();

        assertThat(exchange(
                HttpMethod.DELETE,
                "/api/v1/groups/" + conversationId + "/members/" + member.userId(),
                ownerToken,
                null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> sendAfterRemoval = exchange(
                HttpMethod.POST,
                "/api/v1/conversations/" + conversationId + "/messages",
                memberToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "should fail"));
        assertThat(sendAfterRemoval.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(sendAfterRemoval.getBody().get("code").asText()).isEqualTo("NOT_MEMBER");
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/messages?afterSeq=0&limit=200",
                memberToken,
                null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(downloadMedia(memberToken, mediaId).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        JsonNode revoked = exchange(
                HttpMethod.GET,
                "/api/v1/sync?after=" + beforeRemoval + "&limit=200",
                memberToken,
                null).getBody();
        assertThat(revoked.get("events"))
                .extracting(node -> node.get("eventType").asText())
                .contains("MEMBERSHIP_REVOKED");

        assertThat(addMember(ownerToken, conversationId, member.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode afterRejoin = postMessage(ownerToken, conversationId, "after rejoin");
        JsonNode rejoinedHistory = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/messages?afterSeq=0&limit=200",
                memberToken,
                null).getBody();
        assertThat(rejoinedHistory.get("messages"))
                .extracting(node -> node.get("conversationSeq").asLong())
                .containsExactly(
                        afterRejoin.get("conversationSeq").asLong() - 1,
                        afterRejoin.get("conversationSeq").asLong());
    }

    @Test
    void group_message_sync_fanout_has_one_event_per_active_member() throws Exception {
        TestUser owner = createUser("Fanout owner");
        TestUser first = createUser("Fanout first");
        TestUser second = createUser("Fanout second");
        String ownerToken = login(owner.accountNo(), "fanout-owner");
        String firstToken = login(first.accountNo(), "fanout-first");
        String secondToken = login(second.accountNo(), "fanout-second");

        JsonNode group = createGroup(ownerToken, "Fanout Lounge", "", "PRIVATE");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());
        assertThat(addMember(ownerToken, conversationId, first.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(addMember(ownerToken, conversationId, second.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        postMessage(ownerToken, conversationId, "fanout");

        for (String token : List.of(ownerToken, firstToken, secondToken)) {
            JsonNode page = exchange(
                    HttpMethod.GET,
                    "/api/v1/sync?after=0&limit=200",
                    token,
                    null).getBody();
            assertThat(page.get("events"))
                    .extracting(node -> node.get("eventType").asText())
                    .contains("MESSAGE_CREATED");
        }
    }

    @Test
    void group_read_progress_is_user_level_without_exposing_other_members() throws Exception {
        TestUser owner = createUser("Read owner");
        TestUser member = createUser("Read member");
        TestUser outsider = createUser("Read outsider");
        String ownerToken = login(owner.accountNo(), "read-owner");
        String memberToken = login(member.accountNo(), "read-member");
        String outsiderToken = login(outsider.accountNo(), "read-outsider");
        JsonNode group = createGroup(ownerToken, "Read Lounge", "", "PRIVATE");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());
        assertThat(addMember(ownerToken, conversationId, member.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        long latestSequence = postMessage(ownerToken, conversationId, "read me")
                .get("conversationSeq").asLong();

        JsonNode beforeRead = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/read",
                memberToken,
                null).getBody();
        assertThat(beforeRead.get("states")).hasSize(1);
        assertThat(readSeqFor(beforeRead, member.userId())).isZero();
        long ownerWatermark = highWatermark(ownerToken);
        long memberWatermark = highWatermark(memberToken);

        JsonNode marked = exchange(
                HttpMethod.POST,
                "/api/v1/conversations/" + conversationId + "/read",
                memberToken,
                Map.of("readSeq", latestSequence)).getBody();
        assertThat(readSeqFor(marked, member.userId())).isEqualTo(latestSequence);
        assertThat(jdbc.sql("""
                        SELECT read_seq
                        FROM conversation_members
                        WHERE conversation_id = :conversationId
                          AND user_id = :userId
                        """)
                .param("conversationId", conversationId)
                .param("userId", member.userId())
                .query(Long.class)
                .single()).isEqualTo(latestSequence);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM conversation_read_states
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .query(Long.class)
                .single()).isZero();

        JsonNode memberView = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/read",
                memberToken,
                null).getBody();
        JsonNode ownerView = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/read",
                ownerToken,
                null).getBody();
        assertThat(readSeqFor(memberView, member.userId())).isEqualTo(latestSequence);
        assertThat(ownerView.get("states")).hasSize(1);
        assertThat(readSeqFor(ownerView, owner.userId())).isZero();
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/sync?after=" + memberWatermark + "&limit=200",
                memberToken,
                null).getBody().get("events"))
                .extracting(event -> event.get("eventType").asText())
                .contains("CONVERSATION_READ");
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/sync?after=" + ownerWatermark + "&limit=200",
                ownerToken,
                null).getBody().get("events"))
                .extracting(event -> event.get("eventType").asText())
                .doesNotContain("CONVERSATION_READ");
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/read",
                outsiderToken,
                null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(exchange(
                HttpMethod.DELETE,
                "/api/v1/groups/" + conversationId + "/members/" + member.userId(),
                ownerToken,
                null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(addMember(ownerToken, conversationId, member.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode rejoinedView = exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/read",
                memberToken,
                null).getBody();
        assertThat(readSeqFor(rejoinedView, member.userId())).isZero();
    }

    @Test
    void owner_dissolution_revokes_access_retires_group_number_and_emits_a_dissolution_event()
            throws Exception {
        TestUser owner = createUser("Dissolution owner");
        TestUser member = createUser("Dissolution member");
        String ownerToken = login(owner.accountNo(), "dissolution-owner");
        String memberToken = login(member.accountNo(), "dissolution-member");

        JsonNode group = createGroup(ownerToken, "Dissolution Lounge", "", "PUBLIC");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());
        String groupNo = group.get("groupNo").asText();
        assertThat(addMember(ownerToken, conversationId, member.accountNo()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        postMessage(ownerToken, conversationId, "content before dissolution");

        long ownerWatermark = highWatermark(ownerToken);
        long memberWatermark = highWatermark(memberToken);
        ResponseEntity<JsonNode> dissolved = exchange(
                HttpMethod.DELETE,
                "/api/v1/groups/" + conversationId,
                ownerToken,
                null);
        assertThat(dissolved.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(exchange(HttpMethod.GET, "/api/v1/groups", ownerToken, null)
                .getBody()).isEmpty();
        assertThat(exchange(HttpMethod.GET, "/api/v1/groups", memberToken, null)
                .getBody()).isEmpty();
        assertThat(search(memberToken, groupNo).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/conversations/" + conversationId + "/messages?afterSeq=0&limit=200",
                ownerToken,
                null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(
                HttpMethod.POST,
                "/api/v1/conversations/" + conversationId + "/messages",
                memberToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "must fail"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        for (String token : List.of(ownerToken, memberToken)) {
            JsonNode events = exchange(
                    HttpMethod.GET,
                    "/api/v1/sync?after=" + (token.equals(ownerToken)
                            ? ownerWatermark
                            : memberWatermark) + "&limit=200",
                    token,
                    null).getBody().get("events");
            assertThat(events)
                    .extracting(node -> node.get("eventType").asText())
                    .contains("GROUP_DISSOLVED");
            assertThat(events)
                    .filteredOn(node -> node.get("eventType").asText().equals("GROUP_DISSOLVED"))
                    .allSatisfy(node -> assertThat(node.get("conversationId").asText())
                            .isEqualTo(conversationId.toString()));
        }

        assertThat(jdbc.sql("""
                        SELECT retired_at
                        FROM public_identifiers
                        WHERE public_no = :groupNo
                        """)
                .param("groupNo", groupNo)
                .query(Object.class)
                .single()).isNotNull();
    }

    @Test
    void dissolution_schedules_a_thirty_day_purge_without_removing_governance_audit()
            throws Exception {
        TestUser owner = createUser("Purge owner");
        String ownerToken = login(owner.accountNo(), "purge-owner");
        JsonNode group = createGroup(ownerToken, "Purge Lounge", "", "PRIVATE");
        UUID conversationId = UUID.fromString(group.get("conversationId").asText());
        postMessage(ownerToken, conversationId, "purged content");

        assertThat(exchange(
                HttpMethod.DELETE,
                "/api/v1/groups/" + conversationId,
                ownerToken,
                null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(jdbc.sql("""
                        SELECT status, dissolved_at, purge_after
                        FROM groups
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .query((row, rowNum) -> Map.of(
                        "status", row.getString("status"),
                        "dissolvedAt", row.getObject("dissolved_at"),
                        "purgeAfter", row.getObject("purge_after")))
                .single())
                .containsEntry("status", "DISSOLVED")
                .containsKeys("dissolvedAt", "purgeAfter");

        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM audit_logs
                        WHERE subject_type = 'GROUP'
                          AND subject_id = :conversationId
                          AND event_type = 'GROUP_DISSOLUTION'
                        """)
                .param("conversationId", conversationId)
                .query(Long.class)
                .single()).isEqualTo(1L);
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

    private long highWatermark(String token) {
        return exchange(
                HttpMethod.GET,
                "/api/v1/sync?after=0&until=0",
                token,
                null).getBody().get("highWatermark").asLong();
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

    private ResponseEntity<JsonNode> changeRole(
            String token,
            UUID conversationId,
            UUID userId,
            String role
    ) {
        return exchange(
                HttpMethod.PUT,
                "/api/v1/groups/" + conversationId + "/members/" + userId + "/role",
                token,
                Map.of("role", role));
    }

    private List<Long> systemSequences(JsonNode history) {
        List<Long> sequences = new java.util.ArrayList<>();
        history.get("messages").forEach(message -> {
            if ("SYSTEM".equals(message.get("type").asText())) {
                sequences.add(message.get("conversationSeq").asLong());
            }
        });
        return sequences;
    }

    private long readSeqFor(JsonNode page, UUID userId) {
        for (JsonNode state : page.get("states")) {
            if (state.get("userId").asText().equals(userId.toString())) {
                return state.get("readSeq").asLong();
            }
        }
        throw new AssertionError("Missing read state for " + userId);
    }

    private JsonNode postMessage(String token, UUID conversationId, String text) {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST,
                "/api/v1/conversations/" + conversationId + "/messages",
                token,
                Map.of("clientMsgId", UUID.randomUUID(), "text", text));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode uploadImage(String token) throws Exception {
        BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, new Color(80 + x, 100 + y, 120).getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new NamedByteArrayResource(output.toByteArray(), "group.png"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(
                "/api/v1/media/images?uploadId=" + UUID.randomUUID(),
                HttpMethod.POST,
                new HttpEntity<>(parts, headers),
                JsonNode.class).getBody();
    }

    private ResponseEntity<byte[]> downloadMedia(String token, UUID mediaId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(
                "/api/v1/media/" + mediaId + "?variant=full",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
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
