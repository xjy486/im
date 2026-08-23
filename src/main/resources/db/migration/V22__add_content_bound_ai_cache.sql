ALTER TABLE ai_jobs
    ADD COLUMN cache_key CHAR(64);

CREATE TABLE ai_cache_entries (
    owner_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    cache_key CHAR(64) NOT NULL,
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('SUMMARY')),
    from_seq BIGINT NOT NULL,
    to_seq BIGINT NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    image_input_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    context_digest CHAR(64) NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_user_id, cache_key),
    CHECK (from_seq >= 0 AND to_seq >= from_seq)
);

CREATE INDEX ai_cache_entries_expiry_idx
    ON ai_cache_entries (expires_at);
