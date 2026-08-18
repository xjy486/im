CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')),
    actor_user_id UUID,
    actor_device_id UUID,
    subject_type VARCHAR(32),
    subject_id UUID,
    request_id UUID,
    error_code VARCHAR(80),
    occurred_at TIMESTAMPTZ NOT NULL,
    CHECK ((subject_type IS NULL) = (subject_id IS NULL))
);

CREATE INDEX audit_logs_occurred_at_idx ON audit_logs (occurred_at DESC);
CREATE INDEX audit_logs_actor_user_idx ON audit_logs (actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;
