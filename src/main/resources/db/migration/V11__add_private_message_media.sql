CREATE TABLE media (
    id UUID PRIMARY KEY,
    purpose VARCHAR(32) NOT NULL CHECK (purpose IN ('MESSAGE_IMAGE', 'AVATAR')),
    uploader_id UUID NOT NULL REFERENCES users (id),
    upload_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'TEMP'
        CHECK (state IN ('TEMP', 'BOUND', 'EXPIRED')),
    original_object_key VARCHAR(300) NOT NULL,
    thumbnail_object_key VARCHAR(300) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    width INTEGER NOT NULL CHECK (width > 0),
    height INTEGER NOT NULL CHECK (height > 0),
    byte_size BIGINT NOT NULL CHECK (byte_size > 0),
    sha256 CHAR(64) NOT NULL,
    attached_message_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    bound_at TIMESTAMPTZ,
    expired_at TIMESTAMPTZ,
    objects_deleted_at TIMESTAMPTZ,
    UNIQUE (uploader_id, upload_id),
    CHECK (
        (state = 'TEMP' AND attached_message_id IS NULL AND bound_at IS NULL)
        OR (state = 'BOUND' AND attached_message_id IS NOT NULL AND bound_at IS NOT NULL)
        OR (state = 'EXPIRED' AND expired_at IS NOT NULL)
    )
);

CREATE INDEX media_state_created_idx
    ON media (state, created_at);

CREATE INDEX media_uploader_idx
    ON media (uploader_id, created_at DESC);

ALTER TABLE messages
    DROP CONSTRAINT messages_type_check,
    ALTER COLUMN text_content DROP NOT NULL,
    ADD COLUMN media_id UUID REFERENCES media (id);

ALTER TABLE messages
    ADD CONSTRAINT messages_type_check
        CHECK (type IN ('TEXT', 'IMAGE', 'SYSTEM')),
    ADD CONSTRAINT messages_content_check
        CHECK (
            (type = 'TEXT' AND text_content IS NOT NULL AND media_id IS NULL)
            OR (type = 'IMAGE' AND text_content IS NULL AND media_id IS NOT NULL)
            OR (type = 'SYSTEM' AND text_content IS NULL AND media_id IS NULL)
        );

CREATE UNIQUE INDEX messages_media_id_idx
    ON messages (media_id)
    WHERE media_id IS NOT NULL;
