ALTER TABLE users
    ADD COLUMN searchable_by_account_no BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE contact_requests (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL REFERENCES users (id),
    recipient_id UUID NOT NULL REFERENCES users (id),
    verification VARCHAR(100) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    CHECK (requester_id <> recipient_id),
    CHECK ((status = 'PENDING' AND resolved_at IS NULL) OR (status <> 'PENDING' AND resolved_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_pending_contact_request
    ON contact_requests (requester_id, recipient_id)
    WHERE status = 'PENDING';
CREATE INDEX contact_requests_recipient_idx
    ON contact_requests (recipient_id, status, created_at DESC);
CREATE INDEX contact_requests_requester_idx
    ON contact_requests (requester_id, status, created_at DESC);

CREATE TABLE contacts (
    user_low_id UUID NOT NULL REFERENCES users (id),
    user_high_id UUID NOT NULL REFERENCES users (id),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'REMOVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    removed_at TIMESTAMPTZ,
    PRIMARY KEY (user_low_id, user_high_id),
    CHECK (user_low_id < user_high_id),
    CHECK ((status = 'ACTIVE' AND removed_at IS NULL) OR (status = 'REMOVED' AND removed_at IS NOT NULL))
);
CREATE INDEX contacts_high_idx ON contacts (user_high_id, status);

CREATE TABLE user_blocks (
    blocker_id UUID NOT NULL REFERENCES users (id),
    blocked_id UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);
CREATE INDEX user_blocks_blocked_idx ON user_blocks (blocked_id, blocker_id);

CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    type VARCHAR(16) NOT NULL CHECK (type IN ('C2C', 'GROUP')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'READ_ONLY', 'DISSOLVED')),
    last_seq BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE c2c_conversations (
    conversation_id UUID PRIMARY KEY REFERENCES conversations (id),
    user_low_id UUID NOT NULL REFERENCES users (id),
    user_high_id UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_low_id, user_high_id),
    CHECK (user_low_id < user_high_id)
);

CREATE INDEX c2c_conversations_high_idx ON c2c_conversations (user_high_id);
