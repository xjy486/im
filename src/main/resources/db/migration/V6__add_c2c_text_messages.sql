CREATE TABLE messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations (id),
    conversation_seq BIGINT NOT NULL,
    sender_id UUID NOT NULL REFERENCES users (id),
    client_msg_id UUID NOT NULL,
    type VARCHAR(16) NOT NULL DEFAULT 'TEXT'
        CHECK (type IN ('TEXT')),
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (state IN ('ACTIVE', 'RECALLED', 'MODERATED')),
    text_content TEXT NOT NULL,
    server_accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (sender_id, client_msg_id),
    UNIQUE (conversation_id, conversation_seq)
);

CREATE INDEX messages_conversation_idx
    ON messages (conversation_id, conversation_seq);
CREATE INDEX messages_sender_client_idx
    ON messages (sender_id, client_msg_id);
