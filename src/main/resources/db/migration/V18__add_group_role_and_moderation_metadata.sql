ALTER TABLE messages
    ADD COLUMN system_event_type VARCHAR(64),
    ADD COLUMN system_target_user_id UUID REFERENCES users (id),
    ADD COLUMN system_role VARCHAR(16),
    ADD COLUMN moderated_by_user_id UUID REFERENCES users (id),
    ADD COLUMN moderated_reason VARCHAR(500),
    ADD COLUMN moderated_at TIMESTAMPTZ;

UPDATE messages
SET system_event_type = 'LEGACY'
WHERE type = 'SYSTEM'
  AND system_event_type IS NULL;

UPDATE messages
SET moderated_by_user_id = sender_id,
    moderated_reason = '',
    moderated_at = COALESCE(recalled_at, server_accepted_at)
WHERE state = 'MODERATED'
  AND moderated_by_user_id IS NULL;

ALTER TABLE messages
    ADD CONSTRAINT messages_system_metadata_check CHECK (
        (type = 'SYSTEM'
            AND system_event_type IS NOT NULL
            AND text_content IS NULL
            AND media_id IS NULL)
        OR (type <> 'SYSTEM'
            AND system_event_type IS NULL
            AND system_target_user_id IS NULL
            AND system_role IS NULL)
    ),
    ADD CONSTRAINT messages_moderation_metadata_check CHECK (
        (state = 'MODERATED'
            AND moderated_by_user_id IS NOT NULL
            AND moderated_at IS NOT NULL)
        OR (state <> 'MODERATED'
            AND moderated_by_user_id IS NULL
            AND moderated_reason IS NULL
            AND moderated_at IS NULL)
    );

CREATE INDEX messages_moderated_at_idx
    ON messages (moderated_at)
    WHERE moderated_at IS NOT NULL;
