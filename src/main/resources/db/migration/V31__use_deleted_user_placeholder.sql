ALTER TABLE users
    DROP CONSTRAINT users_status_check;

UPDATE users
SET status = 'DELETED'
WHERE status = 'RETIRED';

ALTER TABLE users
    ADD CONSTRAINT users_status_check
        CHECK (status IN ('ACTIVE', 'DELETED'));

ALTER TABLE users
    DROP CONSTRAINT users_check;

ALTER TABLE users
    ADD CONSTRAINT users_check
        CHECK ((status = 'DELETED') = (retired_at IS NOT NULL));
