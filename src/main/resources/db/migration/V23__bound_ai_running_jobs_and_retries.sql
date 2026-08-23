ALTER TABLE ai_jobs
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0 AND attempt_count <= 2);

WITH running_jobs AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY owner_user_id ORDER BY started_at, created_at, id) AS owner_rank
    FROM ai_jobs
    WHERE status = 'RUNNING'
)
UPDATE ai_jobs job
SET status = 'QUEUED',
    started_at = NULL
FROM running_jobs running
WHERE job.id = running.id
  AND running.owner_rank > 1;

CREATE UNIQUE INDEX ai_jobs_single_running_owner_idx
    ON ai_jobs (owner_user_id)
    WHERE status = 'RUNNING';
