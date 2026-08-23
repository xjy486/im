package com.jitong.im.contract;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AiReliabilityMigrationContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = ContractDependencies.postgres(
            "ai-reliability-migration-password");

    @Test
    void migrating_from_v20_terminalizes_unbudgeted_active_jobs_instead_of_stranding_them() throws Exception {
        migrateTo("20");
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        insertV20Owner(userId, deviceId, conversationId);
        insertV20Job(userId, deviceId, conversationId, "QUEUED");
        insertV20Job(userId, deviceId, conversationId, "RUNNING");

        migrateTo(null);

        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status, error_code, reserved_tokens
                     FROM ai_jobs
                     WHERE owner_user_id = ?
                     ORDER BY status, id
                     """)) {
            statement.setObject(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> statuses = new ArrayList<>();
                while (rows.next()) {
                    statuses.add(rows.getString("status"));
                    assertThat(rows.getString("error_code")).isEqualTo("AI_UPGRADE_CANCELLED");
                    assertThat(rows.getLong("reserved_tokens")).isZero();
                }
                assertThat(statuses).containsExactly("CANCELLED", "CANCELLED");
            }
        }
    }

    private void migrateTo(String version) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        if (version != null) {
            configuration.target(MigrationVersion.fromVersion(version));
        }
        configuration.load().migrate();
    }

    private void insertV20Owner(UUID userId, UUID deviceId, UUID conversationId) throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO users (id, account_no, display_name, password_hash)
                    VALUES ('%s', '79927398713', 'Migration Owner', 'hash')
                    """.formatted(userId));
            connection.createStatement().executeUpdate("""
                    INSERT INTO devices (id, user_id, device_class, installation_id_hash)
                    VALUES ('%s', '%s', 'PC', '%s')
                    """.formatted(deviceId, userId, "a".repeat(64)));
            connection.createStatement().executeUpdate("""
                    INSERT INTO conversations (id, type, status, last_seq)
                    VALUES ('%s', 'C2C', 'ACTIVE', 0)
                    """.formatted(conversationId));
        }
    }

    private void insertV20Job(
            UUID userId,
            UUID deviceId,
            UUID conversationId,
            String status
    ) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_jobs (
                         id, owner_user_id, requesting_device_id, conversation_id,
                         request_id, kind, status, from_seq, to_seq,
                         context_digest, context_json, ai_policy_version,
                         model, prompt_version, started_at, expires_at
                     ) VALUES (?, ?, ?, ?, ?, 'SUMMARY', ?, 0, 0, ?, '{}'::jsonb, 1,
                               'legacy-model', 'summary-v1',
                               CASE WHEN ? = 'RUNNING' THEN CURRENT_TIMESTAMP ELSE NULL END,
                               CURRENT_TIMESTAMP + INTERVAL '30 days')
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, userId);
            statement.setObject(3, deviceId);
            statement.setObject(4, conversationId);
            statement.setObject(5, UUID.randomUUID());
            statement.setString(6, status);
            statement.setString(7, "0".repeat(64));
            statement.setString(8, status);
            statement.executeUpdate();
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
