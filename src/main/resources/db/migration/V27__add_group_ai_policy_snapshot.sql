ALTER TABLE ai_jobs
    ADD COLUMN membership_version BIGINT NOT NULL DEFAULT 0
        CHECK (membership_version >= 0);

CREATE INDEX ai_jobs_active_conversation_owner_idx
    ON ai_jobs (conversation_id, owner_user_id, created_at, id)
    WHERE status IN ('QUEUED', 'RUNNING');
