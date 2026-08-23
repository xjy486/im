ALTER TABLE ai_jobs
    ALTER COLUMN context_json DROP NOT NULL;

CREATE INDEX ai_jobs_expiry_idx
    ON ai_jobs (expires_at, id);
