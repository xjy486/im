CREATE TABLE devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    device_class VARCHAR(16) NOT NULL CHECK (device_class IN ('MOBILE', 'PC')),
    installation_id_hash CHAR(64) NOT NULL,
    trust_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (trust_state IN ('ACTIVE', 'UNTRUSTED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    untrusted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_active_device_per_class
    ON devices (user_id, device_class)
    WHERE trust_state = 'ACTIVE';

CREATE UNIQUE INDEX uq_active_device_installation
    ON devices (user_id, device_class, installation_id_hash)
    WHERE trust_state = 'ACTIVE';

CREATE INDEX devices_user_idx ON devices (user_id, created_at DESC);

ALTER TABLE auth_sessions
    ADD COLUMN device_id UUID REFERENCES devices (id);

CREATE INDEX auth_sessions_device_idx ON auth_sessions (device_id, created_at DESC);

UPDATE auth_sessions
SET status = 'REVOKED',
    revoked_at = CURRENT_TIMESTAMP
WHERE device_id IS NULL
  AND status = 'ACTIVE';

UPDATE refresh_tokens
SET state = 'REVOKED'
WHERE session_id IN (
    SELECT id
    FROM auth_sessions
    WHERE device_id IS NULL
)
  AND state <> 'REVOKED';

ALTER TABLE refresh_tokens
    ADD COLUMN parent_id UUID REFERENCES refresh_tokens (id);

CREATE INDEX refresh_tokens_parent_idx ON refresh_tokens (parent_id);

CREATE TABLE login_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    replaced_device_id UUID NOT NULL REFERENCES devices (id),
    new_installation_id_hash CHAR(64) NOT NULL,
    device_class VARCHAR(16) NOT NULL CHECK (device_class IN ('MOBILE', 'PC')),
    challenge_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX login_challenges_lookup_idx
    ON login_challenges (user_id, device_class, new_installation_id_hash, created_at DESC);
