ALTER TABLE conversation_members
    ADD COLUMN read_seq BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT conversation_members_read_seq_check CHECK (read_seq >= 0);

UPDATE conversation_members member
SET read_seq = state.read_seq
FROM conversation_read_states state
JOIN conversations conversation
  ON conversation.id = state.conversation_id
 AND conversation.type = 'GROUP'
WHERE member.conversation_id = state.conversation_id
  AND member.user_id = state.user_id;

DELETE FROM conversation_read_states state
USING conversations conversation
WHERE conversation.id = state.conversation_id
  AND conversation.type = 'GROUP';
