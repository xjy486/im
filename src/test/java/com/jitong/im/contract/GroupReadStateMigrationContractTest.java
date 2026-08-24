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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class GroupReadStateMigrationContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = ContractDependencies.postgres(
            "group-read-state-migration-password");

    @Test
    void migration_moves_existing_group_progress_into_the_membership_lifecycle() throws Exception {
        migrateTo("27");
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        insertExistingGroupReadState(userId, conversationId, 17L);

        migrateTo(null);

        try (Connection connection = connection();
             PreparedStatement memberState = connection.prepareStatement("""
                     SELECT read_seq
                     FROM conversation_members
                     WHERE conversation_id = ? AND user_id = ?
                     """);
             PreparedStatement legacyState = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM conversation_read_states
                     WHERE conversation_id = ? AND user_id = ?
                     """)) {
            memberState.setObject(1, conversationId);
            memberState.setObject(2, userId);
            try (ResultSet rows = memberState.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("read_seq")).isEqualTo(17L);
            }

            legacyState.setObject(1, conversationId);
            legacyState.setObject(2, userId);
            try (ResultSet rows = legacyState.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
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

    private void insertExistingGroupReadState(
            UUID userId,
            UUID conversationId,
            long readSeq
    ) throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO users (id, account_no, display_name, password_hash)
                    VALUES ('%s', '79927398713', 'Migration Member', 'hash')
                    """.formatted(userId));
            connection.createStatement().executeUpdate("""
                    INSERT INTO conversations (id, type, status, last_seq)
                    VALUES ('%s', 'GROUP', 'ACTIVE', 17)
                    """.formatted(conversationId));
            connection.createStatement().executeUpdate("""
                    INSERT INTO groups (
                        conversation_id, group_no, name, description,
                        visibility, owner_user_id, status
                    ) VALUES (
                        '%s', '60000000000', 'Migration Group', '',
                        'PRIVATE', '%s', 'ACTIVE'
                    )
                    """.formatted(conversationId, userId));
            connection.createStatement().executeUpdate("""
                    INSERT INTO conversation_members (
                        conversation_id, user_id, role, status,
                        history_visible_after_seq, membership_version
                    ) VALUES ('%s', '%s', 'OWNER', 'ACTIVE', 0, 1)
                    """.formatted(conversationId, userId));
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO conversation_read_states (conversation_id, user_id, read_seq)
                    VALUES (?, ?, ?)
                    """)) {
                statement.setObject(1, conversationId);
                statement.setObject(2, userId);
                statement.setLong(3, readSeq);
                assertThat(statement.executeUpdate()).isEqualTo(1);
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
