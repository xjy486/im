package com.jitong.im.ai;

import com.jitong.im.auth.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class AiRepository {

    private final JdbcClient jdbc;

    AiRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    AiConversation findConversation(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT c.id,
                               c.status,
                               c.last_seq,
                               CASE
                                   WHEN cc.user_low_id = :userId THEN cc.user_high_id
                                   ELSE cc.user_low_id
                               END AS peer_user_id,
                               COALESCE(read_state.read_seq, 0) AS owner_read_seq,
                               COALESCE(settings.policy_version, 1) AS policy_version,
                               COALESCE(owner_consent.enabled, FALSE) AS owner_consent,
                               COALESCE(peer_consent.enabled, FALSE) AS peer_consent
                        FROM c2c_conversations cc
                        JOIN conversations c
                          ON c.id = cc.conversation_id
                        JOIN contacts contact
                          ON contact.user_low_id = cc.user_low_id
                         AND contact.user_high_id = cc.user_high_id
                         AND contact.status = 'ACTIVE'
                        LEFT JOIN conversation_read_states read_state
                          ON read_state.conversation_id = c.id
                         AND read_state.user_id = :userId
                        LEFT JOIN conversation_ai_settings settings
                          ON settings.conversation_id = c.id
                        LEFT JOIN conversation_ai_consents owner_consent
                          ON owner_consent.conversation_id = c.id
                         AND owner_consent.user_id = :userId
                        LEFT JOIN conversation_ai_consents peer_consent
                          ON peer_consent.conversation_id = c.id
                         AND peer_consent.user_id = CASE
                             WHEN cc.user_low_id = :userId THEN cc.user_high_id
                             ELSE cc.user_low_id
                         END
                        WHERE cc.conversation_id = :conversationId
                          AND (cc.user_low_id = :userId OR cc.user_high_id = :userId)
                          AND c.status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query((row, rowNum) -> new AiConversation(
                        row.getObject("id", UUID.class),
                        userId,
                        row.getObject("peer_user_id", UUID.class),
                        row.getString("status"),
                        row.getLong("last_seq"),
                        row.getLong("owner_read_seq"),
                        row.getLong("policy_version"),
                        row.getBoolean("owner_consent"),
                        row.getBoolean("peer_consent")))
                .optional()
                .orElse(null);
    }

    AiConversation findConversationForUpdate(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT c.id,
                               c.status,
                               c.last_seq,
                               CASE
                                   WHEN cc.user_low_id = :userId THEN cc.user_high_id
                                   ELSE cc.user_low_id
                               END AS peer_user_id,
                               COALESCE(read_state.read_seq, 0) AS owner_read_seq,
                               COALESCE(settings.policy_version, 1) AS policy_version,
                               COALESCE(owner_consent.enabled, FALSE) AS owner_consent,
                               COALESCE(peer_consent.enabled, FALSE) AS peer_consent
                        FROM c2c_conversations cc
                        JOIN conversations c
                          ON c.id = cc.conversation_id
                        JOIN contacts contact
                          ON contact.user_low_id = cc.user_low_id
                         AND contact.user_high_id = cc.user_high_id
                         AND contact.status = 'ACTIVE'
                        LEFT JOIN conversation_read_states read_state
                          ON read_state.conversation_id = c.id
                         AND read_state.user_id = :userId
                        LEFT JOIN conversation_ai_settings settings
                          ON settings.conversation_id = c.id
                        LEFT JOIN conversation_ai_consents owner_consent
                          ON owner_consent.conversation_id = c.id
                         AND owner_consent.user_id = :userId
                        LEFT JOIN conversation_ai_consents peer_consent
                          ON peer_consent.conversation_id = c.id
                         AND peer_consent.user_id = CASE
                             WHEN cc.user_low_id = :userId THEN cc.user_high_id
                             ELSE cc.user_low_id
                         END
                        WHERE cc.conversation_id = :conversationId
                          AND (cc.user_low_id = :userId OR cc.user_high_id = :userId)
                          AND c.status = 'ACTIVE'
                        FOR UPDATE OF c
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query((row, rowNum) -> new AiConversation(
                        row.getObject("id", UUID.class),
                        userId,
                        row.getObject("peer_user_id", UUID.class),
                        row.getString("status"),
                        row.getLong("last_seq"),
                        row.getLong("owner_read_seq"),
                        row.getLong("policy_version"),
                        row.getBoolean("owner_consent"),
                        row.getBoolean("peer_consent")))
                .optional()
                .orElse(null);
    }

    AiConsentResponse updateConsent(UUID conversationId, UUID userId, boolean enabled) {
        jdbc.sql("""
                        INSERT INTO conversation_ai_settings (
                            conversation_id, enabled, policy_version, updated_at
                        ) VALUES (:conversationId, FALSE, 1, CURRENT_TIMESTAMP)
                        ON CONFLICT (conversation_id) DO NOTHING
                        """)
                .param("conversationId", conversationId)
                .update();
        jdbc.sql("""
                        INSERT INTO conversation_ai_consents (
                            conversation_id, user_id, enabled, updated_at
                        ) VALUES (:conversationId, :userId, :enabled, CURRENT_TIMESTAMP)
                        ON CONFLICT (conversation_id, user_id)
                        DO UPDATE SET enabled = EXCLUDED.enabled,
                                      updated_at = EXCLUDED.updated_at
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .param("enabled", enabled)
                .update();
        jdbc.sql("""
                        UPDATE conversation_ai_settings
                        SET enabled = (
                                SELECT COUNT(*) = 2
                                FROM conversation_ai_consents
                                WHERE conversation_id = :conversationId
                                  AND enabled = TRUE
                            ),
                            policy_version = policy_version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .update();
        AiConversation conversation = findConversation(conversationId, userId);
        return new AiConsentResponse(
                1,
                conversationId,
                userId,
                enabled,
                conversation != null && conversation.enabledForBoth(),
                conversation == null ? 1 : conversation.policyVersion());
    }

    List<AiContextMessage> listContext(
            UUID conversationId,
            long afterSeq,
            long untilSeq,
            int limit
    ) {
        return jdbc.sql("""
                        SELECT id, conversation_seq, sender_id, text_content
                        FROM messages
                        WHERE conversation_id = :conversationId
                          AND conversation_seq > :afterSeq
                          AND conversation_seq <= :untilSeq
                          AND type = 'TEXT'
                          AND state = 'ACTIVE'
                          AND text_content IS NOT NULL
                        ORDER BY conversation_seq DESC
                        LIMIT :limit
                        """)
                .param("conversationId", conversationId)
                .param("afterSeq", afterSeq)
                .param("untilSeq", untilSeq)
                .param("limit", limit)
                .query((row, rowNum) -> new AiContextMessage(
                        row.getObject("id", UUID.class),
                        row.getLong("conversation_seq"),
                        row.getObject("sender_id", UUID.class),
                        row.getString("text_content")))
                .list()
                .reversed();
    }

    AiJobRecord findJob(UUID ownerUserId, UUID jobId) {
        return jdbc.sql(selectJobSql() + " WHERE id = :jobId AND owner_user_id = :ownerUserId")
                .param("jobId", jobId)
                .param("ownerUserId", ownerUserId)
                .query(this::mapJob)
                .optional()
                .orElse(null);
    }

    AiJobRecord findJob(UUID jobId) {
        return jdbc.sql(selectJobSql() + " WHERE id = :jobId")
                .param("jobId", jobId)
                .query(this::mapJob)
                .optional()
                .orElse(null);
    }

    AiJobRecord findByRequest(UUID ownerUserId, UUID requestId) {
        return jdbc.sql(selectJobSql()
                        + " WHERE owner_user_id = :ownerUserId AND request_id = :requestId")
                .param("ownerUserId", ownerUserId)
                .param("requestId", requestId)
                .query(this::mapJob)
                .optional()
                .orElse(null);
    }

    UUID insertJob(
            UUID jobId,
            UUID ownerUserId,
            UUID requestingDeviceId,
            UUID conversationId,
            UUID requestId,
            long fromSeq,
            long toSeq,
            String contextDigest,
            String contextJson,
            long policyVersion,
            String model,
            String promptVersion,
            Instant expiresAt
    ) {
        jdbc.sql("""
                        INSERT INTO ai_jobs (
                            id, owner_user_id, requesting_device_id, conversation_id,
                            request_id, kind, status, from_seq, to_seq,
                            context_digest, context_json, ai_policy_version,
                            model, prompt_version, expires_at
                        ) VALUES (
                            :id, :ownerUserId, :requestingDeviceId, :conversationId,
                            :requestId, 'SUMMARY', 'QUEUED', :fromSeq, :toSeq,
                            :contextDigest, CAST(:contextJson AS jsonb), :policyVersion,
                            :model, :promptVersion, :expiresAt
                        )
                        """)
                .param("id", jobId)
                .param("ownerUserId", ownerUserId)
                .param("requestingDeviceId", requestingDeviceId)
                .param("conversationId", conversationId)
                .param("requestId", requestId)
                .param("fromSeq", fromSeq)
                .param("toSeq", toSeq)
                .param("contextDigest", contextDigest)
                .param("contextJson", contextJson)
                .param("policyVersion", policyVersion)
                .param("model", model)
                .param("promptVersion", promptVersion)
                .param("expiresAt", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return jobId;
    }

    AiJobRecord claimNextQueued(Instant startedAt) {
        return jdbc.sql("""
                        WITH candidate AS (
                            SELECT id
                            FROM ai_jobs
                            WHERE status = 'QUEUED'
                            ORDER BY created_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT 1
                        )
                        UPDATE ai_jobs job
                        SET status = 'RUNNING',
                            started_at = :startedAt
                        FROM candidate
                        WHERE job.id = candidate.id
                        RETURNING job.id, job.owner_user_id, job.requesting_device_id,
                                  job.conversation_id, job.request_id, job.kind, job.status,
                                  job.from_seq, job.to_seq, job.context_digest,
                                  job.context_json::text, job.ai_policy_version,
                                  job.model, job.prompt_version, job.result_json::text,
                                  job.error_code, job.created_at, job.started_at,
                                  job.finished_at, job.expires_at
                        """)
                .param("startedAt", utc(startedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(this::mapJob)
                .optional()
                .orElse(null);
    }

    int succeed(
            UUID jobId,
            UUID ownerUserId,
            String resultJson,
            Instant finishedAt,
            Instant expiresAt
    ) {
        return jdbc.sql("""
                        UPDATE ai_jobs
                        SET status = 'SUCCEEDED',
                            result_json = CAST(:resultJson AS jsonb),
                            error_code = NULL,
                            finished_at = :finishedAt,
                            expires_at = :expiresAt
                        WHERE id = :jobId
                          AND owner_user_id = :ownerUserId
                          AND status = 'RUNNING'
                        """)
                .param("jobId", jobId)
                .param("ownerUserId", ownerUserId)
                .param("resultJson", resultJson)
                .param("finishedAt", utc(finishedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expiresAt", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    int createArtifact(
            UUID jobId,
            UUID ownerUserId,
            String resultJson,
            Instant expiresAt
    ) {
        jdbc.sql("""
                        INSERT INTO ai_artifacts (
                            id, job_id, owner_user_id, artifact_type,
                            content_json, expires_at
                        ) VALUES (
                            :id, :jobId, :ownerUserId, 'SUMMARY',
                            CAST(:contentJson AS jsonb), :expiresAt
                        )
                        ON CONFLICT (job_id)
                        DO UPDATE SET content_json = EXCLUDED.content_json,
                                      expires_at = EXCLUDED.expires_at
                        """)
                .param("id", UuidV7.random())
                .param("jobId", jobId)
                .param("ownerUserId", ownerUserId)
                .param("contentJson", resultJson)
                .param("expiresAt", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return 1;
    }

    int fail(UUID jobId, UUID ownerUserId, String errorCode, Instant finishedAt) {
        return jdbc.sql("""
                        UPDATE ai_jobs
                        SET status = 'FAILED',
                            result_json = NULL,
                            error_code = :errorCode,
                            finished_at = :finishedAt
                        WHERE id = :jobId
                          AND owner_user_id = :ownerUserId
                          AND status = 'RUNNING'
                        """)
                .param("jobId", jobId)
                .param("ownerUserId", ownerUserId)
                .param("errorCode", errorCode)
                .param("finishedAt", utc(finishedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    List<AiArtifactRecord> listArtifacts(UUID ownerUserId, Instant now) {
        return jdbc.sql("""
                        SELECT id, job_id, artifact_type, content_json::text,
                               created_at, expires_at
                        FROM ai_artifacts
                        WHERE owner_user_id = :ownerUserId
                          AND expires_at > :now
                        ORDER BY created_at DESC
                        """)
                .param("ownerUserId", ownerUserId)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((row, rowNum) -> new AiArtifactRecord(
                        row.getObject("id", UUID.class),
                        row.getObject("job_id", UUID.class),
                        row.getString("artifact_type"),
                        row.getString("content_json"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    int deleteArtifact(UUID ownerUserId, UUID artifactId) {
        return jdbc.sql("""
                        DELETE FROM ai_artifacts
                        WHERE id = :artifactId AND owner_user_id = :ownerUserId
                        """)
                .param("artifactId", artifactId)
                .param("ownerUserId", ownerUserId)
                .update();
    }

    int deleteJob(UUID ownerUserId, UUID jobId) {
        return jdbc.sql("""
                        DELETE FROM ai_jobs
                        WHERE owner_user_id = :ownerUserId AND id = :jobId
                        """)
                .param("ownerUserId", ownerUserId)
                .param("jobId", jobId)
                .update();
    }

    void deleteArtifactsForJob(UUID ownerUserId, UUID jobId) {
        jdbc.sql("""
                        DELETE FROM ai_artifacts
                        WHERE owner_user_id = :ownerUserId AND job_id = :jobId
                        """)
                .param("ownerUserId", ownerUserId)
                .param("jobId", jobId)
                .update();
    }

    private String selectJobSql() {
        return """
                SELECT id, owner_user_id, requesting_device_id, conversation_id,
                       request_id, kind, status, from_seq, to_seq,
                       context_digest, context_json::text, ai_policy_version,
                       model, prompt_version, result_json::text, error_code,
                       created_at, started_at, finished_at, expires_at
                FROM ai_jobs
                """;
    }

    private AiJobRecord mapJob(java.sql.ResultSet row, int rowNum) throws java.sql.SQLException {
        return new AiJobRecord(
                row.getObject("id", UUID.class),
                row.getObject("owner_user_id", UUID.class),
                row.getObject("requesting_device_id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getObject("request_id", UUID.class),
                row.getString("kind"),
                row.getString("status"),
                row.getLong("from_seq"),
                row.getLong("to_seq"),
                row.getString("context_digest"),
                row.getString("context_json"),
                row.getLong("ai_policy_version"),
                row.getString("model"),
                row.getString("prompt_version"),
                row.getString("result_json"),
                row.getString("error_code"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                nullableInstant(row, "started_at"),
                nullableInstant(row, "finished_at"),
                nullableInstant(row, "expires_at"));
    }

    private static Instant nullableInstant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    record AiArtifactRecord(
            UUID artifactId,
            UUID jobId,
            String artifactType,
            String contentJson,
            Instant createdAt,
            Instant expiresAt
    ) {
    }
}
