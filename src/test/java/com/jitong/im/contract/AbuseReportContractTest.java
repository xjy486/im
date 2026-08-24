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

class AbuseReportContractTest extends ContractTestEnvironment {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void authenticated_users_can_track_private_reports_for_users_groups_and_messages() throws Exception {
        TestUser alice = createUser("Report Alice");
        TestUser bob = createUser("Report Bob");
        String aliceToken = login(alice.accountNo(), "report-alice");
        String bobToken = login(bob.accountNo(), "report-bob");

        JsonNode request = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests",
                aliceToken,
                Map.of("accountNo", bob.accountNo(), "verification", "hello"))
                .getBody();
        JsonNode accepted = exchange(
                HttpMethod.POST,
                "/api/v1/contact-requests/" + request.get("requestId").asText() + "/accept",
                bobToken,
                null).getBody();
        UUID conversationId = UUID.fromString(accepted.get("conversationId").asText());

        JsonNode message = exchange(
                HttpMethod.POST,
                "/api/v1/conversations/" + conversationId + "/messages",
                aliceToken,
                Map.of("clientMsgId", UUID.randomUUID(), "text", "reportable message"))
                .getBody();
        UUID groupId = UUID.fromString(exchange(
                HttpMethod.POST,
                "/api/v1/groups",
                aliceToken,
                Map.of("name", "Reportable public group", "description", "public", "visibility", "PUBLIC"))
                .getBody()
                .get("conversationId")
                .asText());

        JsonNode userReport = report(aliceToken, "USER", bob.userId(), "HARASSMENT");
        JsonNode groupReport = report(aliceToken, "GROUP", groupId, "SPAM");
        JsonNode messageReport = report(
                aliceToken,
                "MESSAGE",
                UUID.fromString(message.get("messageId").asText()),
                "ILLEGAL_CONTENT");

        assertThat(userReport.get("status").asText()).isEqualTo("OPEN");
        assertThat(userReport.get("reportId").asText()).isNotBlank();
        assertThat(groupReport.get("targetType").asText()).isEqualTo("GROUP");
        assertThat(messageReport.get("targetType").asText()).isEqualTo("MESSAGE");

        JsonNode mine = exchange(
                HttpMethod.GET,
                "/api/v1/abuse-reports",
                aliceToken,
                null).getBody();
        assertThat(mine).hasSize(3);
        assertThat(mine.findValuesAsText("targetType"))
                .containsExactlyInAnyOrder("USER", "GROUP", "MESSAGE");
        assertThat(mine.toString()).doesNotContain("reportable message");

        JsonNode otherUserReports = exchange(
                HttpMethod.GET,
                "/api/v1/abuse-reports",
                bobToken,
                null).getBody();
        assertThat(otherUserReports).isEmpty();
    }

    @Test
    void only_the_protected_admin_surface_can_review_reports_suspend_users_and_pause_public_group_search() throws Exception {
        TestUser alice = createUser("Moderation Alice");
        TestUser bob = createUser("Moderation Bob");
        TestUser charlie = createUser("Moderation Charlie");
        String aliceToken = login(alice.accountNo(), "moderation-alice");
        String bobToken = login(bob.accountNo(), "moderation-bob");
        String charlieToken = login(charlie.accountNo(), "moderation-charlie");

        JsonNode group = exchange(
                HttpMethod.POST,
                "/api/v1/groups",
                aliceToken,
                Map.of("name", "Pause me", "description", "visible before pause", "visibility", "PUBLIC"))
                .getBody();
        UUID groupId = UUID.fromString(group.get("conversationId").asText());
        JsonNode report = report(aliceToken, "USER", bob.userId(), "SPAM");
        UUID reportId = UUID.fromString(report.get("reportId").asText());

        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/admin/abuse-reports",
                aliceToken,
                null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> reviewed = adminExchange(
                HttpMethod.PATCH,
                "/api/v1/admin/abuse-reports/" + reportId,
                Map.of("status", "REVIEWING"));
        assertThat(reviewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reviewed.getBody().get("status").asText()).isEqualTo("REVIEWING");

        ResponseEntity<JsonNode> reports = adminExchange(
                HttpMethod.GET,
                "/api/v1/admin/abuse-reports?status=REVIEWING",
                null);
        assertThat(reports.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reports.getBody()).hasSize(1);
        assertThat(reports.getBody().get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder(
                        "version", "reportId", "reporterUserId", "targetType", "targetId",
                        "reasonCode", "status", "createdAt", "updatedAt", "resolvedAt");
        assertThat(reports.getBody().toString())
                .doesNotContain("correct horse battery staple")
                .doesNotContain("reportable message")
                .doesNotContain("http://");

        ResponseEntity<Void> suspended = adminExchangeVoid(
                HttpMethod.POST,
                "/api/v1/admin/users/" + bob.userId() + "/suspension",
                Map.of("reason", "repeated abuse"));
        assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/users/search?accountNo=" + bob.accountNo(),
                aliceToken,
                null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/protected/test",
                bobToken,
                null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(adminExchangeVoid(
                HttpMethod.POST,
                "/api/v1/admin/groups/" + groupId + "/suspension",
                Map.of("reason", "reported public abuse")).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(
                HttpMethod.GET,
                "/api/v1/groups/search?query=Pause%20me",
                aliceToken,
                null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
                HttpMethod.POST,
                "/api/v1/groups/join-requests/by-group-no",
                charlieToken,
                Map.of("groupNo", group.get("groupNo").asText())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(jdbc.sql("""
                        SELECT status
                        FROM users
                        WHERE id = :userId
                        """)
                .param("userId", bob.userId())
                .query(String.class)
                .single()).isEqualTo("SUSPENDED");
        assertThat(jdbc.sql("""
                        SELECT platform_suspended_at
                        FROM groups
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", groupId)
                .query(java.time.OffsetDateTime.class)
                .single()).isNotNull();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM audit_logs
                        WHERE event_type IN (
                            'ABUSE_REPORT_CREATED',
                            'ABUSE_REPORT_REVIEWED',
                            'USER_SUSPENSION',
                            'GROUP_SUSPENSION'
                        )
                          AND subject_id IN (:reportId, :userId, :groupId)
                        """)
                .param("reportId", reportId)
                .param("userId", bob.userId())
                .param("groupId", groupId)
                .query(Long.class)
                .single()).isEqualTo(4L);
        assertThat(jdbc.sql("""
                        SELECT event_type, COUNT(*)
                        FROM audit_logs
                        WHERE event_type IN (
                            'ABUSE_REPORT_CREATED',
                            'ABUSE_REPORT_REVIEWED',
                            'USER_SUSPENSION',
                            'GROUP_SUSPENSION'
                        )
                          AND subject_id IN (:reportId, :userId, :groupId)
                        GROUP BY event_type
                        """)
                .param("reportId", reportId)
                .param("userId", bob.userId())
                .param("groupId", groupId)
                .query((row, rowNum) -> Map.entry(
                        row.getString("event_type"),
                        row.getLong("count")))
                .list())
                .containsExactlyInAnyOrder(
                        Map.entry("ABUSE_REPORT_CREATED", 1L),
                        Map.entry("ABUSE_REPORT_REVIEWED", 1L),
                        Map.entry("USER_SUSPENSION", 1L),
                        Map.entry("GROUP_SUSPENSION", 1L));
    }

    private JsonNode report(String token, String targetType, UUID targetId, String reasonCode)
            throws Exception {
        return exchange(
                HttpMethod.POST,
                "/api/v1/abuse-reports",
                token,
                Map.of("targetType", targetType, "targetId", targetId, "reasonCode", reasonCode))
                .getBody();
    }

    private ResponseEntity<JsonNode> adminExchange(
            HttpMethod method,
            String path,
            Object body
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        return http.exchange(
                path,
                method,
                new HttpEntity<>(body == null ? null : json(body), headers),
                JsonNode.class);
    }

    private ResponseEntity<Void> adminExchangeVoid(
            HttpMethod method,
            String path,
            Object body
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        return http.exchange(
                path,
                method,
                new HttpEntity<>(body == null ? null : json(body), headers),
                Void.class);
    }

    private ResponseEntity<JsonNode> exchange(
            HttpMethod method,
            String path,
            String token,
            Object body
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(
                path,
                method,
                new HttpEntity<>(body == null ? null : json(body), headers),
                JsonNode.class);
    }

    private TestUser createUser(String displayName) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Api-Key", ContractDependencies.ADMIN_API_KEY);
        ResponseEntity<String> response = http.postForEntity(
                "/api/v1/admin/users",
                new HttpEntity<>(json(Map.of(
                        "displayName", displayName + UUID.randomUUID(),
                        "password", "correct horse battery staple")), headers),
                String.class);
        JsonNode body = objectMapper.readTree(response.getBody());
        return new TestUser(
                UUID.fromString(body.get("userId").asText()),
                body.get("accountNo").asText());
    }

    private String login(String accountNo, String installationId) throws Exception {
        ResponseEntity<String> response = http.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(json(Map.of(
                        "accountNo", accountNo,
                        "password", "correct horse battery staple",
                        "installationId", installationId)), jsonHeaders()),
                String.class);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record TestUser(UUID userId, String accountNo) {
    }
}
