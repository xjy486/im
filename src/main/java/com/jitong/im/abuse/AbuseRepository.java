package com.jitong.im.abuse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class AbuseRepository {

    private final JdbcClient jdbc;

    AbuseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean targetUserExists(UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM users
                            WHERE id = :userId
                              AND status IN ('ACTIVE', 'SUSPENDED')
                        )
                        """)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    boolean reportableGroupExists(UUID groupId, UUID reporterUserId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM groups group_chat
                            WHERE group_chat.conversation_id = :groupId
                              AND group_chat.status = 'ACTIVE'
                              AND (
                                  group_chat.visibility IN ('PUBLIC', 'UNLISTED')
                                  OR EXISTS (
                                      SELECT 1
                                      FROM conversation_members member
                                      WHERE member.conversation_id = group_chat.conversation_id
                                        AND member.user_id = :reporterUserId
                                        AND member.status = 'ACTIVE'
                                  )
                              )
                        )
                        """)
                .param("groupId", groupId)
                .param("reporterUserId", reporterUserId)
                .query(Boolean.class)
                .single();
    }

    boolean reportableMessageExists(UUID messageId, UUID reporterUserId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM messages message
                            JOIN conversations conversation
                              ON conversation.id = message.conversation_id
                            WHERE message.id = :messageId
                              AND (
                                  EXISTS (
                                      SELECT 1
                                      FROM c2c_conversations c2c
                                      WHERE c2c.conversation_id = message.conversation_id
                                        AND (
                                            c2c.user_low_id = :reporterUserId
                                            OR c2c.user_high_id = :reporterUserId
                                        )
                                  )
                                  OR EXISTS (
                                      SELECT 1
                                      FROM groups group_chat
                                      JOIN conversation_members member
                                        ON member.conversation_id = group_chat.conversation_id
                                       AND member.user_id = :reporterUserId
                                       AND member.status = 'ACTIVE'
                                      WHERE group_chat.conversation_id = message.conversation_id
                                        AND group_chat.status IN ('ACTIVE', 'DISSOLVED')
                                  )
                              )
                        )
                        """)
                .param("messageId", messageId)
                .param("reporterUserId", reporterUserId)
                .query(Boolean.class)
                .single();
    }

    AbuseReportRecord insertReport(
            UUID reportId,
            UUID reporterUserId,
            String targetType,
            UUID targetId,
            String reasonCode,
            Instant createdAt
    ) {
        jdbc.sql("""
                        INSERT INTO abuse_reports (
                            id, reporter_user_id, target_type, target_id,
                            reason_code, created_at, updated_at
                        ) VALUES (
                            :id, :reporterUserId, :targetType, :targetId,
                            :reasonCode, :createdAt, :updatedAt
                        )
                        """)
                .param("id", reportId)
                .param("reporterUserId", reporterUserId)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("reasonCode", reasonCode)
                .param("createdAt", utc(createdAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedAt", utc(createdAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return findReport(reportId);
    }

    AbuseReportRecord findReport(UUID reportId) {
        return jdbc.sql("""
                        SELECT id, reporter_user_id, target_type, target_id,
                               reason_code, status, created_at, updated_at, resolved_at
                        FROM abuse_reports
                        WHERE id = :reportId
                        """)
                .param("reportId", reportId)
                .query(this::mapReport)
                .optional()
                .orElse(null);
    }

    List<AbuseReportRecord> listForReporter(UUID reporterUserId) {
        return jdbc.sql("""
                        SELECT id, reporter_user_id, target_type, target_id,
                               reason_code, status, created_at, updated_at, resolved_at
                        FROM abuse_reports
                        WHERE reporter_user_id = :reporterUserId
                        ORDER BY created_at DESC, id DESC
                        """)
                .param("reporterUserId", reporterUserId)
                .query(this::mapReport)
                .list();
    }

    List<AbuseReportRecord> listForAdmin(String status, int limit) {
        return jdbc.sql("""
                        SELECT id, reporter_user_id, target_type, target_id,
                               reason_code, status, created_at, updated_at, resolved_at
                        FROM abuse_reports
                        WHERE (:status IS NULL OR status = :status)
                        ORDER BY created_at ASC, id ASC
                        LIMIT :limit
                        """)
                .param("status", status, Types.VARCHAR)
                .param("limit", limit)
                .query(this::mapReport)
                .list();
    }

    int updateReportStatus(
            UUID reportId,
            String currentStatus,
            String nextStatus,
            Instant updatedAt
    ) {
        return jdbc.sql("""
                        UPDATE abuse_reports
                        SET status = :status,
                            updated_at = :updatedAt,
                            resolved_at = CASE
                                WHEN :nextStatus IN ('RESOLVED', 'DISMISSED') THEN :updatedAt
                                ELSE NULL
                            END
                        WHERE id = :reportId AND status = :currentStatus
                        """)
                .param("reportId", reportId)
                .param("currentStatus", currentStatus)
                .param("status", nextStatus)
                .param("nextStatus", nextStatus)
                .param("updatedAt", utc(updatedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    String userStatus(UUID userId) {
        return jdbc.sql("""
                        SELECT status
                        FROM users
                        WHERE id = :userId
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    List<UUID> activeDeviceIds(UUID userId) {
        return jdbc.sql("""
                        SELECT id
                        FROM devices
                        WHERE user_id = :userId AND trust_state = 'ACTIVE'
                        """)
                .param("userId", userId)
                .query(UUID.class)
                .list();
    }

    int suspendUser(UUID userId, String reason, Instant suspendedAt) {
        return jdbc.sql("""
                        UPDATE users
                        SET status = 'SUSPENDED',
                            suspended_at = :suspendedAt,
                            suspension_reason = :reason
                        WHERE id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("suspendedAt", utc(suspendedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("reason", reason)
                .update();
    }

    int restoreUser(UUID userId) {
        return jdbc.sql("""
                        UPDATE users
                        SET status = 'ACTIVE',
                            suspended_at = NULL,
                            suspension_reason = NULL
                        WHERE id = :userId AND status = 'SUSPENDED'
                        """)
                .param("userId", userId)
                .update();
    }

    void revokeUserCredentials(UUID userId, Instant revokedAt) {
        jdbc.sql("""
                        UPDATE devices
                        SET trust_state = 'UNTRUSTED',
                            push_token_ciphertext = NULL,
                            push_token_digest = NULL,
                            push_token_version = 0,
                            untrusted_at = :revokedAt
                        WHERE user_id = :userId AND trust_state = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("revokedAt", utc(revokedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE auth_sessions
                        SET status = 'REVOKED', revoked_at = :revokedAt
                        WHERE user_id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("revokedAt", utc(revokedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE refresh_tokens
                        SET state = 'REVOKED'
                        WHERE session_id IN (
                            SELECT id
                            FROM auth_sessions
                            WHERE user_id = :userId
                        )
                          AND state <> 'REVOKED'
                        """)
                .param("userId", userId)
                .update();
    }

    String groupStatus(UUID conversationId) {
        return jdbc.sql("""
                        SELECT status
                        FROM groups
                        WHERE conversation_id = :conversationId
                        FOR UPDATE
                        """)
                .param("conversationId", conversationId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    String groupVisibility(UUID conversationId) {
        return jdbc.sql("""
                        SELECT visibility
                        FROM groups
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    int suspendGroup(UUID conversationId, String reason, Instant suspendedAt) {
        return jdbc.sql("""
                        UPDATE groups
                        SET platform_suspended_at = :suspendedAt,
                            platform_suspension_reason = :reason
                        WHERE conversation_id = :conversationId
                          AND status = 'ACTIVE'
                          AND visibility = 'PUBLIC'
                        """)
                .param("conversationId", conversationId)
                .param("suspendedAt", utc(suspendedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("reason", reason)
                .update();
    }

    int restoreGroup(UUID conversationId) {
        return jdbc.sql("""
                        UPDATE groups
                        SET platform_suspended_at = NULL,
                            platform_suspension_reason = NULL
                        WHERE conversation_id = :conversationId
                          AND status = 'ACTIVE'
                          AND platform_suspended_at IS NOT NULL
                        """)
                .param("conversationId", conversationId)
                .update();
    }

    private AbuseReportRecord mapReport(java.sql.ResultSet row, int rowNum)
            throws java.sql.SQLException {
        return new AbuseReportRecord(
                row.getObject("id", UUID.class),
                row.getObject("reporter_user_id", UUID.class),
                row.getString("target_type"),
                row.getObject("target_id", UUID.class),
                row.getString("reason_code"),
                row.getString("status"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                nullableInstant(row, "resolved_at"));
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant nullableInstant(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record AbuseReportRecord(
            UUID reportId,
            UUID reporterUserId,
            String targetType,
            UUID targetId,
            String reasonCode,
            String status,
            Instant createdAt,
            Instant updatedAt,
            Instant resolvedAt
    ) {
    }
}
