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
                        SELECT c.id, c.type, c.last_seq,
                               cc.user_low_id, cc.user_high_id
                        FROM conversations c
                        LEFT JOIN c2c_conversations cc
                          ON cc.conversation_id = c.id
                        LEFT JOIN groups group_chat
                          ON group_chat.conversation_id = c.id
                        LEFT JOIN conversation_members member
                          ON member.conversation_id = c.id
                         AND member.user_id = :userId
                        WHERE c.id = :conversationId
                          AND (
                              (c.type = 'C2C'
                                  AND (cc.user_low_id = :userId
                                      OR cc.user_high_id = :userId))
                              OR (c.type = 'GROUP'
                                  AND c.status = 'ACTIVE'
                                  AND group_chat.status = 'ACTIVE'
                                  AND member.status = 'ACTIVE')
                          )
                        """ + lockClause)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query((row, rowNum) -> {
                    ConversationKind kind = ConversationKind.valueOf(row.getString("type"));
                    List<UUID> readEventRecipients = kind == ConversationKind.GROUP
                            ? List.of(userId)
                            : List.of(
                                    row.getObject("user_low_id", UUID.class),
                                    row.getObject("user_high_id", UUID.class));
                    return new ConversationTarget(
                            row.getObject("id", UUID.class),
                            row.getLong("last_seq"),
                            kind,
                            readEventRecipients);
                })
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

    List<ConversationReadState> listStates(
            ConversationTarget target,
            UUID requestingUserId
    ) {
        if (target.kind() == ConversationKind.GROUP) {
            return List.of(findState(target.conversationId(), requestingUserId));
        }
        UUID conversationId = target.conversationId();
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
            ConversationKind kind,
            List<UUID> readEventRecipients
    ) {
    }

    enum ConversationKind {
        C2C,
        GROUP
    }
}
