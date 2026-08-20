CREATE TABLE conversation_read_states (
    conversation_id UUID NOT NULL REFERENCES conversations (id),
    user_id UUID NOT NULL REFERENCES users (id),
    read_seq BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, user_id),
    CHECK (read_seq >= 0)
);

CREATE INDEX conversation_read_states_user_idx
    ON conversation_read_states (user_id, conversation_id);
