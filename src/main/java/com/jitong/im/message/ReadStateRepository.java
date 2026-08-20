package com.jitong.im.message;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
class ReadStateRepository {

    private final JdbcClient jdbc;

    ReadStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    ConversationTarget lockConversation(UUID conversationId, UUID userId) {
        return conversation(conversationId, userId, true);
    }

    ConversationTarget findConversation(UUID conversationId, UUID userId) {
        return conversation(conversationId, userId, false);
    }

    private ConversationTarget conversation(
            UUID conversationId,
            UUID userId,
            boolean lock
    ) {
        String lockClause = lock ? " FOR UPDATE OF c" : "";
        return jdbc.sql("""
                        SELECT c.id, c.last_seq, cc.user_low_id, cc.user_high_id
                        FROM conversations c
                        JOIN c2c_conversations cc ON cc.conversation_id = c.id
                        WHERE c.id = :conversationId
                          AND (cc.user_low_id = :userId OR cc.user_high_id = :userId)
                        """ + lockClause)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query((row, rowNum) -> new ConversationTarget(
                        row.getObject("id", UUID.class),
                        row.getLong("last_seq"),
                        List.of(
                                row.getObject("user_low_id", UUID.class),
                                row.getObject("user_high_id", UUID.class))))
                .optional()
                .orElse(null);
    }

    long currentReadSeq(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT read_seq
                        FROM conversation_read_states
                        WHERE conversation_id = :conversationId
                          AND user_id = :userId
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    void upsertReadSeq(UUID conversationId, UUID userId, long readSeq) {
        jdbc.sql("""
                        INSERT INTO conversation_read_states (
                            conversation_id, user_id, read_seq, updated_at
                        ) VALUES (
                            :conversationId, :userId, :readSeq, CURRENT_TIMESTAMP
                        )
                        ON CONFLICT (conversation_id, user_id)
                        DO UPDATE SET read_seq = GREATEST(
                                conversation_read_states.read_seq,
                                EXCLUDED.read_seq
                            ),
                            updated_at = EXCLUDED.updated_at
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .param("readSeq", readSeq)
                .update();
    }

    List<ConversationReadState> listStates(UUID conversationId) {
        return jdbc.sql("""
                        SELECT participants.user_id,
                               COALESCE(states.read_seq, 0) AS read_seq
                        FROM (
                            SELECT user_low_id AS user_id
                            FROM c2c_conversations
                            WHERE conversation_id = :conversationId
                            UNION ALL
                            SELECT user_high_id AS user_id
                            FROM c2c_conversations
                            WHERE conversation_id = :conversationId
                        ) participants
                        LEFT JOIN conversation_read_states states
                          ON states.conversation_id = :conversationId
                         AND states.user_id = participants.user_id
                        ORDER BY participants.user_id
                        """)
                .param("conversationId", conversationId)
                .query((row, rowNum) -> new ConversationReadState(
                        conversationId,
                        row.getObject("user_id", UUID.class),
                        row.getLong("read_seq")))
                .list();
    }

    ConversationReadState findState(UUID conversationId, UUID userId) {
        return new ConversationReadState(
                conversationId,
                userId,
                currentReadSeq(conversationId, userId));
    }

    record ConversationTarget(
            UUID conversationId,
            long lastSequence,
            List<UUID> participants
    ) {
    }
}
