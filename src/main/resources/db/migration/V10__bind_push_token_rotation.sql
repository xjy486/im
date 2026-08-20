ALTER TABLE devices
    ADD COLUMN push_token_digest CHAR(64),
    ADD COLUMN push_token_version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX devices_push_token_digest_idx
    ON devices (push_token_digest)
    WHERE push_token_digest IS NOT NULL;
