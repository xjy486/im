CREATE TABLE ai_daily_budgets (
    owner_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    budget_date DATE NOT NULL,
    limit_tokens BIGINT NOT NULL CHECK (limit_tokens > 0),
    reserved_tokens BIGINT NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0),
    used_tokens BIGINT NOT NULL DEFAULT 0 CHECK (used_tokens >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (owner_user_id, budget_date),
    CHECK (reserved_tokens + used_tokens <= limit_tokens)
);

ALTER TABLE ai_jobs
    ADD COLUMN budget_date DATE,
    ADD COLUMN reserved_tokens BIGINT NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0);

UPDATE ai_jobs
SET budget_date = (created_at AT TIME ZONE 'Asia/Shanghai')::date
WHERE budget_date IS NULL;

ALTER TABLE ai_jobs
    ALTER COLUMN budget_date SET NOT NULL;

-- V20 jobs were accepted without an atomic reservation. They cannot safely be
-- admitted into the new worker, so terminalize them instead of leaving them
-- permanently QUEUED or allowing an unbudgeted RUNNING writeback.
UPDATE ai_jobs
SET status = 'CANCELLED',
    result_json = NULL,
    error_code = 'AI_UPGRADE_CANCELLED',
    finished_at = CURRENT_TIMESTAMP,
    reserved_tokens = 0
WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX ai_jobs_owner_budget_idx
    ON ai_jobs (owner_user_id, budget_date)
    WHERE reserved_tokens > 0;
