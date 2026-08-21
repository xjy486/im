UPDATE c2c_conversations conversation
SET readonly_low_display_name = low.display_name,
    readonly_low_avatar_fallback = CASE
        WHEN low.display_name IS NULL OR BTRIM(low.display_name) = '' THEN '?'
        ELSE SUBSTRING(low.display_name FROM 1 FOR 1)
    END,
    readonly_high_display_name = high.display_name,
    readonly_high_avatar_fallback = CASE
        WHEN high.display_name IS NULL OR BTRIM(high.display_name) = '' THEN '?'
        ELSE SUBSTRING(high.display_name FROM 1 FOR 1)
    END
FROM conversations c
JOIN users low ON low.id = conversation.user_low_id
JOIN users high ON high.id = conversation.user_high_id
WHERE c.id = conversation.conversation_id
  AND c.status = 'READ_ONLY';
