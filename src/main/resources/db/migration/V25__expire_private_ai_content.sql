ALTER TABLE ai_jobs
    ALTER COLUMN context_json DROP NOT NULL;

UPDATE ai_jobs
SET context_json = NULL
WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED');

CREATE INDEX ai_jobs_expiry_idx
    ON ai_jobs (expires_at, id);
