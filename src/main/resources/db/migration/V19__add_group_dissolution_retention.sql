ALTER TABLE groups
    ADD COLUMN dissolved_at TIMESTAMPTZ,
    ADD COLUMN purge_after TIMESTAMPTZ;

ALTER TABLE groups
    ADD CONSTRAINT groups_dissolution_retention_check CHECK (
        (status = 'ACTIVE' AND dissolved_at IS NULL AND purge_after IS NULL)
        OR (status = 'DISSOLVED'
            AND dissolved_at IS NOT NULL
            AND purge_after IS NOT NULL
            AND purge_after >= dissolved_at)
    );

CREATE INDEX groups_purge_after_idx
    ON groups (purge_after)
    WHERE status = 'DISSOLVED';

CREATE TABLE group_dissolution_audit (
    conversation_id UUID PRIMARY KEY REFERENCES groups (conversation_id),
    group_no CHAR(11) NOT NULL,
    owner_user_id UUID NOT NULL REFERENCES users (id),
    dissolved_at TIMESTAMPTZ NOT NULL,
    purged_at TIMESTAMPTZ
);

CREATE INDEX group_dissolution_audit_purged_idx
    ON group_dissolution_audit (purged_at, dissolved_at);
