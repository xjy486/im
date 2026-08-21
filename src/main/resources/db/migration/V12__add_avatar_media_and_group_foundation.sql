ALTER TABLE users
    ADD COLUMN avatar_media_id UUID REFERENCES media (id),
    ADD COLUMN avatar_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE media
    ADD COLUMN attached_entity_id UUID,
    ADD COLUMN attached_entity_type VARCHAR(16);

ALTER TABLE media
    DROP CONSTRAINT IF EXISTS media_check,
    DROP CONSTRAINT IF EXISTS media_state_check;

UPDATE media
SET attached_message_id = NULL,
    attached_entity_id = NULL,
    attached_entity_type = NULL,
    bound_at = NULL
WHERE state = 'EXPIRED';

ALTER TABLE media
    ADD CONSTRAINT media_state_check CHECK (
        (state = 'TEMP' AND attached_message_id IS NULL AND attached_entity_id IS NULL
            AND attached_entity_type IS NULL AND bound_at IS NULL)
        OR (state = 'BOUND' AND (
            (purpose = 'MESSAGE_IMAGE' AND attached_message_id IS NOT NULL AND attached_entity_id IS NULL
                AND attached_entity_type IS NULL AND bound_at IS NOT NULL)
            OR (purpose = 'AVATAR' AND attached_message_id IS NULL AND attached_entity_id IS NOT NULL
                AND attached_entity_type IN ('USER', 'GROUP') AND bound_at IS NOT NULL)
        ))
        OR (state = 'EXPIRED' AND expired_at IS NOT NULL
            AND attached_message_id IS NULL
            AND attached_entity_id IS NULL
            AND attached_entity_type IS NULL
            AND bound_at IS NULL)
    );

CREATE INDEX media_attached_entity_idx
    ON media (purpose, attached_entity_id);

CREATE TABLE groups (
    conversation_id UUID PRIMARY KEY REFERENCES conversations (id),
    group_no CHAR(11) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    visibility VARCHAR(16) NOT NULL CHECK (visibility IN ('PUBLIC', 'UNLISTED', 'PRIVATE')),
    owner_user_id UUID NOT NULL REFERENCES users (id),
    avatar_media_id UUID REFERENCES media (id),
    avatar_version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISSOLVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE conversation_members (
    conversation_id UUID NOT NULL REFERENCES conversations (id),
    user_id UUID NOT NULL REFERENCES users (id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'REMOVED', 'LEFT')),
    history_visible_after_seq BIGINT NOT NULL DEFAULT 0,
    membership_version BIGINT NOT NULL DEFAULT 1,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX conversation_members_user_idx
    ON conversation_members (user_id, status, conversation_id);
