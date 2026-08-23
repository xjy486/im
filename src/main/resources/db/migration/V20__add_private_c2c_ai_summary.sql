CREATE TABLE conversation_ai_settings (
    conversation_id UUID PRIMARY KEY REFERENCES conversations (id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    policy_version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (policy_version >= 1)
);

CREATE TABLE conversation_ai_consents (
    conversation_id UUID NOT NULL REFERENCES conversations (id),
    user_id UUID NOT NULL REFERENCES users (id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX conversation_ai_consents_user_idx
    ON conversation_ai_consents (user_id, conversation_id);

CREATE TABLE ai_jobs (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users (id),
    requesting_device_id UUID NOT NULL REFERENCES devices (id),
    conversation_id UUID NOT NULL REFERENCES conversations (id),
    request_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('SUMMARY')),
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    from_seq BIGINT NOT NULL,
    to_seq BIGINT NOT NULL,
    context_digest CHAR(64) NOT NULL,
    context_json JSONB NOT NULL,
    ai_policy_version BIGINT NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    result_json JSONB,
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (from_seq >= 0 AND to_seq >= from_seq),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')) = (finished_at IS NOT NULL)),
    CHECK ((status = 'SUCCEEDED') = (result_json IS NOT NULL AND error_code IS NULL)),
    CHECK ((status IN ('FAILED', 'CANCELLED', 'EXPIRED')) = (error_code IS NOT NULL AND result_json IS NULL)),
    UNIQUE (owner_user_id, request_id)
);

CREATE INDEX ai_jobs_queue_idx
    ON ai_jobs (status, created_at, id)
    WHERE status = 'QUEUED';
CREATE INDEX ai_jobs_owner_idx
    ON ai_jobs (owner_user_id, created_at DESC);

CREATE TABLE ai_artifacts (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL UNIQUE REFERENCES ai_jobs (id),
    owner_user_id UUID NOT NULL REFERENCES users (id),
    artifact_type VARCHAR(16) NOT NULL CHECK (artifact_type IN ('SUMMARY')),
    content_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ai_artifacts_owner_idx
    ON ai_artifacts (owner_user_id, created_at DESC);
