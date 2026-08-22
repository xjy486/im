CREATE TABLE group_invites (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES groups (conversation_id),
    created_by_user_id UUID NOT NULL REFERENCES users (id),
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    max_uses INTEGER NOT NULL DEFAULT 100
        CHECK (max_uses > 0 AND max_uses <= 10000),
    use_count INTEGER NOT NULL DEFAULT 0
        CHECK (use_count >= 0 AND use_count <= max_uses),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    UNIQUE (id, conversation_id),
    CHECK ((status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL))
);

CREATE INDEX group_invites_lookup_idx
    ON group_invites (conversation_id, status, expires_at);

CREATE TABLE group_join_requests (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES groups (conversation_id),
    user_id UUID NOT NULL REFERENCES users (id),
    invite_id UUID REFERENCES group_invites (id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    reviewed_by_user_id UUID REFERENCES users (id),
    FOREIGN KEY (invite_id, conversation_id)
        REFERENCES group_invites (id, conversation_id),
    CHECK ((status = 'PENDING' AND resolved_at IS NULL AND reviewed_by_user_id IS NULL)
        OR (status IN ('APPROVED', 'REJECTED', 'CANCELLED')
            AND resolved_at IS NOT NULL
            AND reviewed_by_user_id IS NOT NULL)
        OR (status = 'EXPIRED' AND resolved_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_pending_group_join_request
    ON group_join_requests (conversation_id, user_id)
    WHERE status = 'PENDING';

CREATE INDEX group_join_requests_review_idx
    ON group_join_requests (conversation_id, status, created_at DESC);

CREATE INDEX group_join_requests_user_idx
    ON group_join_requests (user_id, status, created_at DESC);

CREATE TABLE group_bans (
    conversation_id UUID NOT NULL REFERENCES groups (conversation_id),
    user_id UUID NOT NULL REFERENCES users (id),
    actor_user_id UUID NOT NULL REFERENCES users (id),
    reason VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, user_id),
    CHECK (user_id <> actor_user_id)
);

CREATE INDEX group_bans_user_idx
    ON group_bans (user_id, conversation_id);
