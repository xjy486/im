package com.jitong.im.message;

import com.jitong.im.auth.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class MessageRepository {

    private final JdbcClient jdbc;

    MessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void lockSender(UUID senderId) {
        jdbc.sql("""
                        SELECT id
                        FROM users
                        WHERE id = :senderId AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("senderId", senderId)
                .query(UUID.class)
                .single();
    }

    ConversationTarget lockConversation(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT cc.conversation_id,
                               CASE
                                   WHEN cc.user_low_id = :userId THEN cc.user_high_id
                                   ELSE cc.user_low_id
                               END AS peer_id,
                               c.status
                        FROM c2c_conversations cc
                        JOIN conversations c ON c.id = cc.conversation_id
                        WHERE cc.conversation_id = :conversationId
                          AND (cc.user_low_id = :userId OR cc.user_high_id = :userId)
                        FOR UPDATE OF c
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query((row, rowNum) -> new ConversationTarget(
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("peer_id", UUID.class),
                        row.getString("status")))
                .optional()
                .orElse(null);
    }

    MessageRecord findByClientMessageId(UUID senderId, UUID clientMsgId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, sender_id, client_msg_id,
                               conversation_seq, type, state, text_content,
                               server_accepted_at
                        FROM messages
                        WHERE sender_id = :senderId AND client_msg_id = :clientMsgId
                        """)
                .param("senderId", senderId)
                .param("clientMsgId", clientMsgId)
                .query(this::mapMessage)
                .optional()
                .orElse(null);
    }

    long nextConversationSequence(UUID conversationId) {
        return jdbc.sql("""
                        UPDATE conversations
                        SET last_seq = last_seq + 1
                        WHERE id = :conversationId
                        RETURNING last_seq
                        """)
                .param("conversationId", conversationId)
                .query(Long.class)
                .single();
    }

    MessageRecord insertTextMessage(
            UUID messageId,
            UUID conversationId,
            long conversationSeq,
            UUID senderId,
            UUID clientMsgId,
            String text,
            Instant acceptedAt
    ) {
        jdbc.sql("""
                        INSERT INTO messages (
                            id, conversation_id, conversation_seq, sender_id,
                            client_msg_id, text_content, server_accepted_at
                        ) VALUES (
                            :id, :conversationId, :conversationSeq, :senderId,
                            :clientMsgId, :text, :acceptedAt
                        )
                        """)
                .param("id", messageId)
                .param("conversationId", conversationId)
                .param("conversationSeq", conversationSeq)
                .param("senderId", senderId)
                .param("clientMsgId", clientMsgId)
                .param("text", text)
                .param("acceptedAt", utc(acceptedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return findById(messageId);
    }

    MessageRecord findById(UUID messageId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, sender_id, client_msg_id,
                               conversation_seq, type, state, text_content,
                               server_accepted_at
                        FROM messages
                        WHERE id = :messageId
                        """)
                .param("messageId", messageId)
                .query(this::mapMessage)
                .single();
    }

    ConversationTarget findConversation(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT cc.conversation_id,
                               CASE
                                   WHEN cc.user_low_id = :userId THEN cc.user_high_id
                                   ELSE cc.user_low_id
                               END AS peer_id,
                               c.status
                        FROM c2c_conversations cc
                        JOIN conversations c ON c.id = cc.conversation_id
                        WHERE cc.conversation_id = :conversationId
                          AND (cc.user_low_id = :userId OR cc.user_high_id = :userId)
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query((row, rowNum) -> new ConversationTarget(
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("peer_id", UUID.class),
                        row.getString("status")))
                .optional()
                .orElse(null);
    }

    List<MessageRecord> listMessages(UUID conversationId, long afterSequence, int limit) {
        return jdbc.sql("""
                        SELECT id, conversation_id, sender_id, client_msg_id,
                               conversation_seq, type, state, text_content,
                               server_accepted_at
                        FROM messages
                        WHERE conversation_id = :conversationId
                          AND conversation_seq > :afterSequence
                        ORDER BY conversation_seq ASC
                        LIMIT :limit
                        """)
                .param("conversationId", conversationId)
                .param("afterSequence", afterSequence)
                .param("limit", limit)
                .query(this::mapMessage)
                .list();
    }

    private MessageRecord mapMessage(java.sql.ResultSet row, int rowNum) throws java.sql.SQLException {
        return new MessageRecord(
                row.getObject("id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getObject("sender_id", UUID.class),
                row.getObject("client_msg_id", UUID.class),
                row.getLong("conversation_seq"),
                row.getString("type"),
                row.getString("state"),
                row.getString("text_content"),
                row.getObject("server_accepted_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    record ConversationTarget(UUID conversationId, UUID peerUserId, String status) {
    }
}
