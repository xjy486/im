ALTER TABLE ai_jobs DROP CONSTRAINT ai_jobs_kind_check;
ALTER TABLE ai_jobs
    ADD CONSTRAINT ai_jobs_kind_check
    CHECK (kind IN ('SUMMARY', 'SMART_REPLY', 'EXTRACTION'));

ALTER TABLE ai_artifacts DROP CONSTRAINT ai_artifacts_artifact_type_check;
ALTER TABLE ai_artifacts
    ADD CONSTRAINT ai_artifacts_artifact_type_check
    CHECK (artifact_type IN ('SUMMARY', 'SMART_REPLY', 'EXTRACTION'));

ALTER TABLE ai_cache_entries DROP CONSTRAINT ai_cache_entries_kind_check;
ALTER TABLE ai_cache_entries
    ADD CONSTRAINT ai_cache_entries_kind_check
    CHECK (kind IN ('SUMMARY', 'SMART_REPLY', 'EXTRACTION'));

CREATE TABLE ai_action_items (
    id UUID PRIMARY KEY,
    source_job_id UUID REFERENCES ai_jobs (id) ON DELETE SET NULL,
    owner_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    assignee_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    title VARCHAR(500) NOT NULL,
    details VARCHAR(4000) NOT NULL,
    due_at TIMESTAMPTZ,
    priority VARCHAR(16) NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    confidence DOUBLE PRECISION NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    source_message_ids JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CHECK (jsonb_typeof(source_message_ids) = 'array'),
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX ai_action_items_owner_idx
    ON ai_action_items (owner_user_id, created_at DESC, id);
CREATE INDEX ai_action_items_job_idx
    ON ai_action_items (source_job_id)
    WHERE source_job_id IS NOT NULL;
