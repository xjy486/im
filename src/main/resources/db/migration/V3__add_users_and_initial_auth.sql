CREATE TABLE public_identifiers (
    public_no CHAR(11) PRIMARY KEY,
    entity_type VARCHAR(16) NOT NULL CHECK (entity_type IN ('USER', 'GROUP')),
    entity_id UUID NOT NULL UNIQUE,
    retired_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (public_no ~ '^[1-9][0-9]{10}$')
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    account_no CHAR(11) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    password_hash VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retired_at TIMESTAMPTZ,
    CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL))
);

CREATE INDEX users_status_idx ON users (status);

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    access_token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX auth_sessions_user_idx ON auth_sessions (user_id, created_at DESC);
CREATE INDEX auth_sessions_active_access_idx ON auth_sessions (access_token_hash)
    WHERE status = 'ACTIVE';

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth_sessions (id),
    family_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (state IN ('ACTIVE', 'CONSUMED', 'REVOKED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX refresh_tokens_session_idx ON refresh_tokens (session_id, created_at DESC);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id, state);
