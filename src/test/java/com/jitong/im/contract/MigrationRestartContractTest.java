package com.jitong.im.contract;

import com.jitong.im.JitongApplication;
import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MigrationRestartContractTest {

    private static final String MINIO_ACCESS_KEY = "restart-test-access";
    private static final String MINIO_SECRET_KEY = "restart-test-secret-key";
    private static final UUID AUDIT_SENTINEL_ID = UUID.fromString("5a3328b4-5271-4dad-a7fd-42553b6800f1");
    private static final UUID REQUEST_ID = UUID.fromString("17ace382-d515-4785-b589-3977309d7fb1");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = ContractDependencies.postgres(
            "restart-test-database-password");

    @Container
    static final GenericContainer<?> MINIO = ContractDependencies.minio(
            MINIO_ACCESS_KEY,
            MINIO_SECRET_KEY);

    @Test
    void an_empty_database_migrates_and_the_service_restarts_against_the_same_schema() throws Exception {
        try (ConfigurableApplicationContext firstStart = startService()) {
            assertHealthy(firstStart);
            assertSuccessfulMigrationVersions(
                    "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16");
            insertRestartSentinel();
            insertAuditSentinel(firstStart);
        }

        try (ConfigurableApplicationContext restarted = startService()) {
            assertHealthy(restarted);
            assertSuccessfulMigrationVersions(
                    "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16");
            assertRestartSentinelPreserved();
            assertAuditSentinelPreserved();
        }
    }

    private ConfigurableApplicationContext startService() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("contract-test", Map.ofEntries(
                Map.entry("server.port", "0"),
                Map.entry("spring.main.banner-mode", "off"),
                Map.entry("logging.level.root", "WARN"),
                Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
                Map.entry("spring.datasource.username", POSTGRES.getUsername()),
                Map.entry("spring.datasource.password", POSTGRES.getPassword()),
                Map.entry("jitong.media.endpoint", ContractDependencies.minioEndpoint(MINIO)),
                Map.entry("jitong.media.access-key", MINIO_ACCESS_KEY),
                Map.entry("jitong.media.secret-key", MINIO_SECRET_KEY),
                Map.entry("jitong.media.bucket", "restart-test-media")
        )));
        return new SpringApplicationBuilder(JitongApplication.class)
                .environment(environment)
                .run();
    }

    private void assertHealthy(ConfigurableApplicationContext context) throws Exception {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/system/health"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    private void assertSuccessfulMigrationVersions(String... expected) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank");
             ResultSet rows = statement.executeQuery()) {
            List<String> versions = new ArrayList<>();
            while (rows.next()) {
                versions.add(rows.getString(1));
            }
            assertThat(versions).containsExactly(expected);
        }
    }

    private void insertRestartSentinel() throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO service_metadata (metadata_key, metadata_value) VALUES (?, ?)")) {
            statement.setString(1, "restart-sentinel");
            statement.setString(2, "preserve-me");
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void assertRestartSentinelPreserved() throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT metadata_value FROM service_metadata WHERE metadata_key = ?")) {
            statement.setString(1, "restart-sentinel");
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("preserve-me");
            }
        }
    }

    private void insertAuditSentinel(ConfigurableApplicationContext context) {
        context.getBean(SecurityAuditSink.class).record(new SecurityAuditEvent(
                AUDIT_SENTINEL_ID,
                SecurityAuditEventType.LOGIN,
                AuditOutcome.REJECTED,
                null,
                null,
                null,
                null,
                REQUEST_ID,
                ApiErrorDefinition.INVALID_REQUEST,
                Instant.parse("2026-08-18T03:00:00Z")));
    }

    private void assertAuditSentinelPreserved() throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT event_type FROM audit_logs WHERE id = ?")) {
            statement.setObject(1, AUDIT_SENTINEL_ID);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("LOGIN");
            }
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
