ALTER TABLE users
    ADD COLUMN password_must_change BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN temporary_password_used BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX users_password_must_change_idx
    ON users (id)
    WHERE password_must_change = TRUE;
