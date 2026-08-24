ALTER TABLE users
    ADD COLUMN suspended_at TIMESTAMPTZ,
    ADD COLUMN suspension_reason VARCHAR(500);

ALTER TABLE users
    DROP CONSTRAINT users_status_check;

ALTER TABLE users
    ADD CONSTRAINT users_status_check
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'));

ALTER TABLE users
    ADD CONSTRAINT users_suspension_check
        CHECK (
            (status = 'SUSPENDED' AND suspended_at IS NOT NULL AND suspension_reason IS NOT NULL)
            OR (status <> 'SUSPENDED' AND suspended_at IS NULL AND suspension_reason IS NULL)
        );

ALTER TABLE groups
    ADD COLUMN platform_suspended_at TIMESTAMPTZ,
    ADD COLUMN platform_suspension_reason VARCHAR(500);

ALTER TABLE groups
    ADD CONSTRAINT groups_platform_suspension_check
        CHECK (
            (status = 'ACTIVE'
                AND (platform_suspended_at IS NULL) = (platform_suspension_reason IS NULL))
            OR (status = 'DISSOLVED'
                AND platform_suspended_at IS NULL
                AND platform_suspension_reason IS NULL)
        );

DROP INDEX groups_public_search_idx;

CREATE INDEX groups_public_search_idx
    ON groups
    USING GIN ((name_normalized || ' ' || description_normalized) gin_trgm_ops)
    WHERE status = 'ACTIVE'
      AND visibility = 'PUBLIC'
      AND platform_suspended_at IS NULL;

CREATE TABLE abuse_reports (
    id UUID PRIMARY KEY,
    reporter_user_id UUID NOT NULL REFERENCES users (id),
    target_type VARCHAR(16) NOT NULL
        CHECK (target_type IN ('USER', 'GROUP', 'MESSAGE')),
    target_id UUID NOT NULL,
    reason_code VARCHAR(64) NOT NULL
        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'REVIEWING', 'RESOLVED', 'DISMISSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    CHECK (
        (status IN ('RESOLVED', 'DISMISSED')) = (resolved_at IS NOT NULL)
    )
);

CREATE INDEX abuse_reports_reporter_idx
    ON abuse_reports (reporter_user_id, created_at DESC);

CREATE INDEX abuse_reports_status_idx
    ON abuse_reports (status, created_at ASC);
