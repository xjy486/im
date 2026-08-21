ALTER TABLE messages
    ADD COLUMN recalled_at TIMESTAMPTZ;

ALTER TABLE messages
    DROP CONSTRAINT messages_content_check,
    ADD CONSTRAINT messages_content_check CHECK (
        (state IN ('RECALLED', 'MODERATED') AND text_content IS NULL AND media_id IS NULL)
        OR (state = 'ACTIVE' AND (
            (type = 'TEXT' AND text_content IS NOT NULL AND media_id IS NULL)
            OR (type = 'IMAGE' AND text_content IS NULL AND media_id IS NOT NULL)
            OR (type = 'SYSTEM' AND text_content IS NULL AND media_id IS NULL)
        ))
    ),
    ADD CONSTRAINT messages_recalled_at_check CHECK (
        (state = 'RECALLED') = (recalled_at IS NOT NULL)
    );

CREATE INDEX messages_recalled_at_idx
    ON messages (recalled_at)
    WHERE recalled_at IS NOT NULL;
