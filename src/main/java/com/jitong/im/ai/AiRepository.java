package com.jitong.im.ai;

import com.jitong.im.auth.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
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

    boolean lockActiveOwnerForUpdate(UUID ownerUserId) {
        return jdbc.sql("""
                        SELECT id
                        FROM users
                        WHERE id = :ownerUserId AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("ownerUserId", ownerUserId)
                .query(UUID.class)
                .optional()
                .isPresent();
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
                        JOIN users owner_user
                          ON owner_user.id = :userId
                         AND owner_user.status = 'ACTIVE'
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
                        JOIN users owner_user
                          ON owner_user.id = :userId
                         AND owner_user.status = 'ACTIVE'
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

    AiJobRecord findJobForUpdate(UUID ownerUserId, UUID jobId) {
        return jdbc.sql(selectJobSql()
                        + " WHERE id = :jobId AND owner_user_id = :ownerUserId FOR UPDATE")
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
            String cacheKey,
            LocalDate budgetDate,
            long reservedTokens,
            String model,
            String promptVersion,
            Instant expiresAt
    ) {
        jdbc.sql("""
                        INSERT INTO ai_jobs (
                            id, owner_user_id, requesting_device_id, conversation_id,
                            request_id, kind, status, from_seq, to_seq,
                            context_digest, context_json, ai_policy_version,
                            cache_key, budget_date, reserved_tokens,
                            model, prompt_version, expires_at
                        ) VALUES (
                            :id, :ownerUserId, :requestingDeviceId, :conversationId,
                            :requestId, 'SUMMARY', 'QUEUED', :fromSeq, :toSeq,
                            :contextDigest, CAST(:contextJson AS jsonb), :policyVersion,
                            :cacheKey, :budgetDate, :reservedTokens,
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
                .param("cacheKey", cacheKey)
                .param("budgetDate", budgetDate)
                .param("reservedTokens", reservedTokens)
                .param("model", model)
                .param("promptVersion", promptVersion)
                .param("expiresAt", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return jobId;
    }

    UUID insertCachedJob(
            UUID jobId,
            UUID ownerUserId,
            UUID requestingDeviceId,
            UUID conversationId,
            UUID requestId,
            long fromSeq,
            long toSeq,
            String contextDigest,
            long policyVersion,
            String cacheKey,
            LocalDate budgetDate,
            String model,
            String promptVersion,
            String resultJson,
            Instant finishedAt,
            Instant expiresAt
    ) {
        jdbc.sql("""
                        INSERT INTO ai_jobs (
                            id, owner_user_id, requesting_device_id, conversation_id,
                            request_id, kind, status, from_seq, to_seq,
                            context_digest, context_json, ai_policy_version,
                            cache_key, budget_date, reserved_tokens,
                            model, prompt_version, result_json,
                            started_at, finished_at, expires_at
                        ) VALUES (
                            :id, :ownerUserId, :requestingDeviceId, :conversationId,
                            :requestId, 'SUMMARY', 'SUCCEEDED', :fromSeq, :toSeq,
                            :contextDigest, NULL, :policyVersion,
                            :cacheKey, :budgetDate, 0,
                            :model, :promptVersion, CAST(:resultJson AS jsonb),
                            :finishedAt, :finishedAt, :expiresAt
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
                .param("policyVersion", policyVersion)
                .param("cacheKey", cacheKey)
                .param("budgetDate", budgetDate)
                .param("model", model)
                .param("promptVersion", promptVersion)
                .param("resultJson", resultJson)
                .param("finishedAt", utc(finishedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expiresAt", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return jobId;
    }

    AiCacheEntry findCache(UUID ownerUserId, String cacheKey, Instant now) {
        return jdbc.sql("""
                        SELECT cache_key, result_json::text, expires_at
                        FROM ai_cache_entries
                        WHERE owner_user_id = :ownerUserId
                          AND cache_key = :cacheKey
                          AND expires_at > :now
                        """)
                .param("ownerUserId", ownerUserId)
                .param("cacheKey", cacheKey)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((row, rowNumber) -> new AiCacheEntry(
                        row.getString("cache_key"),
                        row.getString("result_json"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional()
                .orElse(null);
    }

    void createCacheEntry(AiJobRecord job, String resultJson, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO ai_cache_entries (
                            owner_user_id, cache_key, conversation_id, kind,
                            from_seq, to_seq, provider, model, prompt_version,
                            image_input_enabled, context_digest, result_json, expires_at
                        ) VALUES (
                            :ownerUserId, :cacheKey, :conversationId, :kind,
                            :fromSeq, :toSeq, 'openai-compatible', :model, :promptVersion,
                            FALSE, :contextDigest, CAST(:resultJson AS jsonb), :expiresAt
                        )
                        ON CONFLICT (owner_user_id, cache_key)
                        DO UPDATE SET result_json = EXCLUDED.result_json,
                                      created_at = CURRENT_TIMESTAMP,
                                      expires_at = EXCLUDED.expires_at
                        """)
                .param("ownerUserId", job.ownerUserId())
                .param("cacheKey", job.cacheKey())
                .param("conversationId", job.conversationId())
                .param("kind", job.kind())
                .param("fromSeq", job.fromSeq())
                .param("toSeq", job.toSeq())
                .param("model", job.model())
                .param("promptVersion", job.promptVersion())
                .param("contextDigest", job.contextDigest())
                .param("resultJson", resultJson)
                .param("expiresAt", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    boolean reserveBudget(
            UUID ownerUserId,
            LocalDate budgetDate,
            long limitTokens,
            long reserveTokens
    ) {
        jdbc.sql("""
                        INSERT INTO ai_daily_budgets (
                            owner_user_id, budget_date, limit_tokens,
                            reserved_tokens, used_tokens, version
                        ) VALUES (
                            :ownerUserId, :budgetDate, :limitTokens, 0, 0, 0
                        )
                        ON CONFLICT (owner_user_id, budget_date) DO NOTHING
                        """)
                .param("ownerUserId", ownerUserId)
                .param("budgetDate", budgetDate)
                .param("limitTokens", limitTokens)
                .update();
        return jdbc.sql("""
                        UPDATE ai_daily_budgets
                        SET reserved_tokens = reserved_tokens + :reserveTokens,
                            version = version + 1
                        WHERE owner_user_id = :ownerUserId
                          AND budget_date = :budgetDate
                          AND used_tokens + reserved_tokens + :reserveTokens <= limit_tokens
                        """)
                .param("ownerUserId", ownerUserId)
                .param("budgetDate", budgetDate)
                .param("reserveTokens", reserveTokens)
                .update() == 1;
    }

    long countQueuedJobs(UUID ownerUserId) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM ai_jobs
                        WHERE owner_user_id = :ownerUserId
                          AND status = 'QUEUED'
                        """)
                .param("ownerUserId", ownerUserId)
                .query(Long.class)
                .single();
    }

    AiJobRecord claimNextQueued(Instant startedAt) {
        return jdbc.sql("""
                        WITH candidate AS (
                            SELECT job.id
                            FROM ai_jobs job
                            JOIN ai_daily_budgets budget
                              ON budget.owner_user_id = job.owner_user_id
                             AND budget.budget_date = job.budget_date
                            WHERE job.status = 'QUEUED'
                              AND job.expires_at > :startedAt
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM ai_jobs running
                                  WHERE running.owner_user_id = job.owner_user_id
                                    AND running.status = 'RUNNING'
                              )
                            ORDER BY job.created_at, job.id
                            FOR UPDATE OF job, budget SKIP LOCKED
                            LIMIT 1
                        )
                        UPDATE ai_jobs job
                        SET status = 'RUNNING',
                            started_at = :startedAt,
                            attempt_count = attempt_count + 1
                        FROM candidate
                        WHERE job.id = candidate.id
                        RETURNING job.id, job.owner_user_id, job.requesting_device_id,
                                  job.conversation_id, job.request_id, job.kind, job.status,
                                  job.from_seq, job.to_seq, job.context_digest,
                                  job.context_json::text, job.ai_policy_version,
                                  job.cache_key, job.budget_date, job.reserved_tokens,
                                  job.attempt_count, job.input_tokens, job.output_tokens,
                                  job.model, job.prompt_version, job.result_json::text,
                                  job.error_code, job.created_at, job.started_at,
                                  job.finished_at, job.expires_at
                        """)
                .param("startedAt", utc(startedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(this::mapJob)
                .optional()
                .orElse(null);
    }

    List<AiJobRecord> findExpiredActiveJobsForUpdate(Instant now, int limit) {
        return jdbc.sql(selectJobSql() + """
                         WHERE status IN ('QUEUED', 'RUNNING')
                           AND expires_at <= :now
                         ORDER BY expires_at, id
                         FOR UPDATE SKIP LOCKED
                         LIMIT :limit
                        """)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query(this::mapJob)
                .list();
    }

    List<AiJobRecord> findStaleRunningJobsForUpdate(Instant cutoff, int limit) {
        return jdbc.sql(selectJobSql() + """
                         WHERE status = 'RUNNING'
                           AND started_at <= :cutoff
                         ORDER BY started_at, id
                         FOR UPDATE SKIP LOCKED
                         LIMIT :limit
                        """)
                .param("cutoff", utc(cutoff), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query(this::mapJob)
                .list();
    }

    int requeue(UUID jobId, UUID ownerUserId, int expectedAttemptCount) {
        return jdbc.sql("""
                        UPDATE ai_jobs
                        SET status = 'QUEUED',
                            started_at = NULL,
                            error_code = NULL
                        WHERE id = :jobId
                          AND owner_user_id = :ownerUserId
                          AND status = 'RUNNING'
                          AND attempt_count < 2
                          AND attempt_count = :expectedAttemptCount
                        """)
                .param("jobId", jobId)
                .param("ownerUserId", ownerUserId)
                .param("expectedAttemptCount", expectedAttemptCount)
                .update();
    }

    int succeed(
            UUID jobId,
            UUID ownerUserId,
            String resultJson,
            int inputTokens,
            int outputTokens,
            int expectedAttemptCount,
            Instant finishedAt,
            Instant expiresAt
    ) {
        return jdbc.sql("""
                        UPDATE ai_jobs
                        SET status = 'SUCCEEDED',
                            result_json = CAST(:resultJson AS jsonb),
                            error_code = NULL,
                            reserved_tokens = 0,
                            input_tokens = :inputTokens,
                            output_tokens = :outputTokens,
                            context_json = NULL,
                            finished_at = :finishedAt,
                            expires_at = :expiresAt
                        WHERE id = :jobId
                          AND owner_user_id = :ownerUserId
                          AND status = 'RUNNING'
                          AND attempt_count = :expectedAttemptCount
                        """)
                .param("jobId", jobId)
                .param("ownerUserId", ownerUserId)
                .param("resultJson", resultJson)
                .param("inputTokens", inputTokens)
                .param("outputTokens", outputTokens)
                .param("expectedAttemptCount", expectedAttemptCount)
                .param("finishedAt", utc(finishedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expiresAt", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    void settleSucceededBudget(AiJobRecord job, long actualTokens) {
        int updated = jdbc.sql("""
                        UPDATE ai_daily_budgets
                        SET reserved_tokens = reserved_tokens - :reservedTokens,
                            used_tokens = used_tokens + :actualTokens,
                            version = version + 1
                        WHERE owner_user_id = :ownerUserId
                          AND budget_date = :budgetDate
                          AND reserved_tokens >= :reservedTokens
                          AND used_tokens + reserved_tokens - :reservedTokens + :actualTokens
                              <= limit_tokens
                        """)
                .param("ownerUserId", job.ownerUserId())
                .param("budgetDate", job.budgetDate())
                .param("reservedTokens", job.reservedTokens())
                .param("actualTokens", actualTokens)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("AI budget could not be settled");
        }
    }

    void releaseBudget(AiJobRecord job) {
        if (job.reservedTokens() == 0) {
            return;
        }
        int updated = jdbc.sql("""
                        UPDATE ai_daily_budgets
                        SET reserved_tokens = reserved_tokens - :reservedTokens,
                            version = version + 1
                        WHERE owner_user_id = :ownerUserId
                          AND budget_date = :budgetDate
                          AND reserved_tokens >= :reservedTokens
                        """)
                .param("ownerUserId", job.ownerUserId())
                .param("budgetDate", job.budgetDate())
                .param("reservedTokens", job.reservedTokens())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("AI budget reservation could not be released");
        }
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

    int fail(
            UUID jobId,
            UUID ownerUserId,
            int expectedAttemptCount,
            String errorCode,
            Instant finishedAt
    ) {
        return jdbc.sql("""
                        UPDATE ai_jobs
                        SET status = 'FAILED',
                            result_json = NULL,
                            context_json = NULL,
                            error_code = :errorCode,
                            reserved_tokens = 0,
                            finished_at = :finishedAt
                        WHERE id = :jobId
                          AND owner_user_id = :ownerUserId
                          AND status = 'RUNNING'
                          AND attempt_count = :expectedAttemptCount
                        """)
                .param("jobId", jobId)
                .param("ownerUserId", ownerUserId)
                .param("expectedAttemptCount", expectedAttemptCount)
                .param("errorCode", errorCode)
                .param("finishedAt", utc(finishedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    int expire(
            UUID jobId,
            UUID ownerUserId,
            int expectedAttemptCount,
            String errorCode,
            Instant finishedAt
    ) {
        return jdbc.sql("""
                        UPDATE ai_jobs
                        SET status = 'EXPIRED',
                            context_json = NULL,
                            result_json = NULL,
                            error_code = :errorCode,
                            reserved_tokens = 0,
                            finished_at = :finishedAt
                        WHERE id = :jobId
                          AND owner_user_id = :ownerUserId
                          AND status IN ('QUEUED', 'RUNNING')
                          AND attempt_count = :expectedAttemptCount
                        """)
                .param("jobId", jobId)
                .param("ownerUserId", ownerUserId)
                .param("expectedAttemptCount", expectedAttemptCount)
                .param("errorCode", errorCode)
                .param("finishedAt", utc(finishedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    int deleteExpiredArtifacts(Instant now) {
        return jdbc.sql("DELETE FROM ai_artifacts WHERE expires_at <= :now")
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    int deleteExpiredCacheEntries(Instant now) {
        return jdbc.sql("DELETE FROM ai_cache_entries WHERE expires_at <= :now")
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    int deleteExpiredTerminalJobs(Instant now, Instant expiredMetadataCutoff) {
        return jdbc.sql("""
                        DELETE FROM ai_jobs
                        WHERE (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                               AND expires_at <= :now)
                           OR (status = 'EXPIRED' AND finished_at <= :expiredMetadataCutoff)
                        """)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expiredMetadataCutoff", utc(expiredMetadataCutoff), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    void eraseForRetirement(UUID ownerUserId) {
        jdbc.sql("""
                        UPDATE conversation_ai_settings
                        SET enabled = FALSE,
                            policy_version = policy_version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE conversation_id IN (
                            SELECT conversation_id
                            FROM conversation_ai_consents
                            WHERE user_id = :ownerUserId
                        )
                        """)
                .param("ownerUserId", ownerUserId)
                .update();
        jdbc.sql("DELETE FROM conversation_ai_consents WHERE user_id = :ownerUserId")
                .param("ownerUserId", ownerUserId)
                .update();
        jdbc.sql("""
                        DELETE FROM outbox
                        WHERE event_type LIKE 'AI_%'
                          AND target_device_id IN (
                              SELECT id FROM devices WHERE user_id = :ownerUserId
                          )
                        """)
                .param("ownerUserId", ownerUserId)
                .update();
        jdbc.sql("""
                        DELETE FROM user_sync_events
                        WHERE user_id = :ownerUserId AND event_type LIKE 'AI_%'
                        """)
                .param("ownerUserId", ownerUserId)
                .update();
        jdbc.sql("DELETE FROM ai_artifacts WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", ownerUserId)
                .update();
        jdbc.sql("DELETE FROM ai_cache_entries WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", ownerUserId)
                .update();
        jdbc.sql("DELETE FROM ai_jobs WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", ownerUserId)
                .update();
        jdbc.sql("DELETE FROM ai_daily_budgets WHERE owner_user_id = :ownerUserId")
                .param("ownerUserId", ownerUserId)
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

    AiArtifactDeletionRecord findArtifactDeletionContext(UUID ownerUserId, UUID artifactId) {
        return jdbc.sql("""
                        SELECT artifact.id AS artifact_id,
                               artifact.job_id,
                               job.conversation_id,
                               job.cache_key
                        FROM ai_artifacts artifact
                        JOIN ai_jobs job
                          ON job.id = artifact.job_id
                         AND job.owner_user_id = artifact.owner_user_id
                        WHERE artifact.id = :artifactId
                          AND artifact.owner_user_id = :ownerUserId
                        """)
                .param("artifactId", artifactId)
                .param("ownerUserId", ownerUserId)
                .query((row, rowNumber) -> new AiArtifactDeletionRecord(
                        row.getObject("artifact_id", UUID.class),
                        row.getObject("job_id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getString("cache_key")))
                .optional()
                .orElse(null);
    }

    List<AiJobRecord> findJobsForContentDeletionForUpdate(
            UUID ownerUserId,
            UUID jobId,
            String cacheKey
    ) {
        return jdbc.sql(selectJobSql() + """
                         WHERE owner_user_id = :ownerUserId
                           AND (
                               id = :jobId
                               OR (:cacheKey IS NOT NULL AND cache_key = :cacheKey)
                           )
                         ORDER BY id
                         FOR UPDATE
                        """)
                .param("ownerUserId", ownerUserId)
                .param("jobId", jobId)
                .param("cacheKey", cacheKey)
                .query(this::mapJob)
                .list();
    }

    List<AiArtifactDeletionRecord> findArtifactsForContentDeletionForUpdate(
            UUID ownerUserId,
            UUID jobId,
            String cacheKey
    ) {
        return jdbc.sql("""
                        SELECT artifact.id AS artifact_id,
                               artifact.job_id,
                               job.conversation_id,
                               job.cache_key
                        FROM ai_artifacts artifact
                        JOIN ai_jobs job
                          ON job.id = artifact.job_id
                         AND job.owner_user_id = artifact.owner_user_id
                        WHERE artifact.owner_user_id = :ownerUserId
                          AND (
                              job.id = :jobId
                              OR (:cacheKey IS NOT NULL AND job.cache_key = :cacheKey)
                          )
                        ORDER BY artifact.id
                        FOR UPDATE OF artifact
                        """)
                .param("ownerUserId", ownerUserId)
                .param("jobId", jobId)
                .param("cacheKey", cacheKey)
                .query((row, rowNumber) -> new AiArtifactDeletionRecord(
                        row.getObject("artifact_id", UUID.class),
                        row.getObject("job_id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getString("cache_key")))
                .list();
    }

    void eraseResultCopies(
            UUID ownerUserId,
            UUID jobId,
            String cacheKey,
            Instant deletedAt
    ) {
        jdbc.sql("""
                        DELETE FROM ai_artifacts artifact
                        USING ai_jobs job
                        WHERE artifact.job_id = job.id
                          AND artifact.owner_user_id = :ownerUserId
                          AND job.owner_user_id = :ownerUserId
                          AND (
                              job.id = :jobId
                              OR (:cacheKey IS NOT NULL AND job.cache_key = :cacheKey)
                          )
                        """)
                .param("ownerUserId", ownerUserId)
                .param("jobId", jobId)
                .param("cacheKey", cacheKey)
                .update();
        jdbc.sql("""
                        UPDATE ai_jobs
                        SET status = 'CANCELLED',
                            context_json = NULL,
                            result_json = NULL,
                            error_code = 'AI_USER_DELETED',
                            reserved_tokens = 0,
                            finished_at = COALESCE(finished_at, :deletedAt)
                        WHERE owner_user_id = :ownerUserId
                          AND (
                              id = :jobId
                              OR (:cacheKey IS NOT NULL AND cache_key = :cacheKey)
                          )
                        """)
                .param("ownerUserId", ownerUserId)
                .param("jobId", jobId)
                .param("cacheKey", cacheKey)
                .param("deletedAt", utc(deletedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        deleteCacheEntry(ownerUserId, cacheKey);
    }

    void deleteCacheEntry(UUID ownerUserId, String cacheKey) {
        if (cacheKey == null) {
            return;
        }
        jdbc.sql("""
                        DELETE FROM ai_cache_entries
                        WHERE owner_user_id = :ownerUserId AND cache_key = :cacheKey
                        """)
                .param("ownerUserId", ownerUserId)
                .param("cacheKey", cacheKey)
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
                       cache_key, budget_date, reserved_tokens, attempt_count,
                       input_tokens, output_tokens,
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
                row.getString("cache_key"),
                row.getObject("budget_date", LocalDate.class),
                row.getLong("reserved_tokens"),
                row.getInt("attempt_count"),
                row.getInt("input_tokens"),
                row.getInt("output_tokens"),
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

    record AiArtifactDeletionRecord(
            UUID artifactId,
            UUID jobId,
            UUID conversationId,
            String cacheKey
    ) {
    }
}
