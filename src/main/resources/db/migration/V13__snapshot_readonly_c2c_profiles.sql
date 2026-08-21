ALTER TABLE c2c_conversations
    ADD COLUMN readonly_low_display_name VARCHAR(128),
    ADD COLUMN readonly_low_avatar_fallback VARCHAR(8),
    ADD COLUMN readonly_high_display_name VARCHAR(128),
    ADD COLUMN readonly_high_avatar_fallback VARCHAR(8);
