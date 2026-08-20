CREATE TABLE user_sync_counters (
    user_id UUID PRIMARY KEY REFERENCES users (id),
    last_seq BIGINT NOT NULL DEFAULT 0,
    min_available_seq BIGINT NOT NULL DEFAULT 1,
    CHECK (last_seq >= 0),
    CHECK (min_available_seq >= 1),
    CHECK (min_available_seq <= last_seq + 1)
);

CREATE TABLE user_sync_events (
    user_id UUID NOT NULL REFERENCES users (id),
    sync_seq BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    entity_id UUID NOT NULL,
    conversation_id UUID REFERENCES conversations (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, sync_seq)
);

CREATE INDEX user_sync_events_lookup_idx
    ON user_sync_events (user_id, sync_seq);

CREATE TABLE device_sync_states (
    device_id UUID PRIMARY KEY REFERENCES devices (id),
    user_id UUID NOT NULL REFERENCES users (id),
    last_acked_seq BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (last_acked_seq >= 0)
);

CREATE INDEX device_sync_states_user_idx
    ON device_sync_states (user_id, last_acked_seq);

CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    entity_id UUID NOT NULL,
    conversation_id UUID REFERENCES conversations (id),
    sync_seq BIGINT NOT NULL,
    target_device_id UUID NOT NULL REFERENCES devices (id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (attempt_count >= 0),
    UNIQUE (target_device_id, event_type, entity_id, sync_seq)
);

CREATE INDEX outbox_pending_idx
    ON outbox (next_attempt_at, created_at)
    WHERE status <> 'COMPLETED';
