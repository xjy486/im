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

    boolean isGroupConversation(UUID conversationId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM groups
                            WHERE conversation_id = :conversationId
                              AND status = 'ACTIVE'
                        )
                        """)
                .param("conversationId", conversationId)
                .query(Boolean.class)
                .single();
    }

    boolean canDeviceReceiveGroupEvent(UUID deviceId, UUID conversationId, long syncSeq) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM devices device
                            JOIN user_sync_events event
                              ON event.user_id = device.user_id
                             AND event.sync_seq = :syncSeq
                             AND event.conversation_id = :conversationId
                            JOIN conversation_members member
                              ON member.user_id = device.user_id
                             AND member.conversation_id = :conversationId
                             AND member.status = 'ACTIVE'
                             AND event.created_at >= member.joined_at
                             AND EXISTS (
                                 SELECT 1
                                 FROM messages message
                                 WHERE message.id = event.entity_id
                                   AND message.conversation_seq > member.history_visible_after_seq
                             )
                            JOIN groups group_chat
                              ON group_chat.conversation_id = :conversationId
                            WHERE device.id = :deviceId
                              AND device.trust_state = 'ACTIVE'
                        )
                        """)
                .param("deviceId", deviceId)
                .param("conversationId", conversationId)
                .param("syncSeq", syncSeq)
                .query(Boolean.class)
                .single();
    }

    GroupConversationTarget lockGroupConversation(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT c.id, c.status, member.history_visible_after_seq
                        FROM conversations c
                        JOIN groups g
                          ON g.conversation_id = c.id
                         AND g.status = 'ACTIVE'
                        JOIN conversation_members member
                          ON member.conversation_id = c.id
                         AND member.user_id = :userId
                         AND member.status = 'ACTIVE'
                        WHERE c.id = :conversationId
                          AND c.type = 'GROUP'
                          AND c.status = 'ACTIVE'
                        FOR UPDATE OF c
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query((row, rowNum) -> new GroupConversationTarget(
                        row.getObject("id", UUID.class),
                        row.getString("status"),
                        row.getLong("history_visible_after_seq")))
                .optional()
                .orElse(null);
    }

    GroupConversationTarget lockGroupConversation(UUID conversationId) {
        return jdbc.sql("""
                        SELECT c.id, c.status, 0 AS history_visible_after_seq
                        FROM conversations c
                        JOIN groups g
                          ON g.conversation_id = c.id
                         AND g.status = 'ACTIVE'
                        WHERE c.id = :conversationId
                          AND c.type = 'GROUP'
                          AND c.status = 'ACTIVE'
                        FOR UPDATE OF c
                        """)
                .param("conversationId", conversationId)
                .query((row, rowNum) -> new GroupConversationTarget(
                        row.getObject("id", UUID.class),
                        row.getString("status"),
                        row.getLong("history_visible_after_seq")))
                .optional()
                .orElse(null);
    }

    GroupConversationTarget findGroupConversation(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT c.id, c.status, member.history_visible_after_seq
                        FROM conversations c
                        JOIN groups g
                          ON g.conversation_id = c.id
                         AND g.status = 'ACTIVE'
                        JOIN conversation_members member
                          ON member.conversation_id = c.id
                         AND member.user_id = :userId
                         AND member.status = 'ACTIVE'
                        WHERE c.id = :conversationId
                          AND c.type = 'GROUP'
                          AND c.status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query((row, rowNum) -> new GroupConversationTarget(
                        row.getObject("id", UUID.class),
                        row.getString("status"),
                        row.getLong("history_visible_after_seq")))
                .optional()
                .orElse(null);
    }

    MessageRecord findByClientMessageId(UUID senderId, UUID clientMsgId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, sender_id, client_msg_id,
                               conversation_seq, type, state, text_content,
                               media_id, server_accepted_at, recalled_at
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

    MessageRecord insertImageMessage(
            UUID messageId,
            UUID conversationId,
            long conversationSeq,
            UUID senderId,
            UUID clientMsgId,
            UUID mediaId,
            Instant acceptedAt
    ) {
        jdbc.sql("""
                        INSERT INTO messages (
                            id, conversation_id, conversation_seq, sender_id,
                            client_msg_id, type, text_content, media_id, server_accepted_at
                        ) VALUES (
                            :id, :conversationId, :conversationSeq, :senderId,
                            :clientMsgId, 'IMAGE', NULL, :mediaId, :acceptedAt
                        )
                        """)
                .param("id", messageId)
                .param("conversationId", conversationId)
                .param("conversationSeq", conversationSeq)
                .param("senderId", senderId)
                .param("clientMsgId", clientMsgId)
                .param("mediaId", mediaId)
                .param("acceptedAt", utc(acceptedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return findById(messageId);
    }

    MessageRecord insertSystemMessage(
            UUID messageId,
            UUID conversationId,
            long conversationSeq,
            UUID senderId,
            UUID clientMsgId,
            Instant acceptedAt
    ) {
        jdbc.sql("""
                        INSERT INTO messages (
                            id, conversation_id, conversation_seq, sender_id,
                            client_msg_id, type, state, text_content, media_id,
                            server_accepted_at
                        ) VALUES (
                            :id, :conversationId, :conversationSeq, :senderId,
                            :clientMsgId, 'SYSTEM', 'ACTIVE', NULL, NULL,
                            :acceptedAt
                        )
                        """)
                .param("id", messageId)
                .param("conversationId", conversationId)
                .param("conversationSeq", conversationSeq)
                .param("senderId", senderId)
                .param("clientMsgId", clientMsgId)
                .param("acceptedAt", utc(acceptedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return findById(messageId);
    }

    MessageRecord findById(UUID messageId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, sender_id, client_msg_id,
                               conversation_seq, type, state, text_content,
                               media_id, server_accepted_at, recalled_at
                        FROM messages
                        WHERE id = :messageId
                        """)
                .param("messageId", messageId)
                .query(this::mapMessage)
                .single();
    }

    UUID findConversationId(UUID messageId) {
        return jdbc.sql("""
                        SELECT conversation_id
                        FROM messages
                        WHERE id = :messageId
                        """)
                .param("messageId", messageId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    MessageRecord findByIdForUpdate(UUID messageId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, sender_id, client_msg_id,
                               conversation_seq, type, state, text_content,
                               media_id, server_accepted_at, recalled_at
                        FROM messages
                        WHERE id = :messageId
                        FOR UPDATE
                        """)
                .param("messageId", messageId)
                .query(this::mapMessage)
                .optional()
                .orElse(null);
    }

    void recallMessage(UUID messageId, Instant recalledAt) {
        jdbc.sql("""
                        UPDATE messages
                        SET state = 'RECALLED',
                            text_content = NULL,
                            media_id = NULL,
                            recalled_at = :recalledAt
                        WHERE id = :messageId AND state = 'ACTIVE'
                        """)
                .param("messageId", messageId)
                .param("recalledAt", utc(recalledAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    UUID findUserIdForDevice(UUID deviceId) {
        return jdbc.sql("""
                        SELECT user_id
                        FROM devices
                        WHERE id = :deviceId AND trust_state = 'ACTIVE'
                        """)
                .param("deviceId", deviceId)
                .query(UUID.class)
                .optional()
                .orElse(null);
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
                               media_id, server_accepted_at, recalled_at
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

    List<MessageRecord> listGroupMessages(
            UUID conversationId,
            long afterSequence,
            long historyVisibleAfterSeq,
            int limit
    ) {
        return jdbc.sql("""
                        SELECT id, conversation_id, sender_id, client_msg_id,
                               conversation_seq, type, state, text_content,
                               media_id, server_accepted_at, recalled_at
                        FROM messages
                        WHERE conversation_id = :conversationId
                          AND conversation_seq > GREATEST(:afterSequence, :historyBoundary)
                        ORDER BY conversation_seq ASC
                        LIMIT :limit
                        """)
                .param("conversationId", conversationId)
                .param("afterSequence", afterSequence)
                .param("historyBoundary", historyVisibleAfterSeq)
                .param("limit", limit)
                .query(this::mapMessage)
                .list();
    }

    List<UUID> conversationParticipants(UUID conversationId) {
        return jdbc.sql("""
                        SELECT user_low_id
                        FROM c2c_conversations
                        WHERE conversation_id = :conversationId
                        UNION ALL
                        SELECT user_high_id
                        FROM c2c_conversations
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .query(UUID.class)
                .list();
    }

    List<UUID> groupActiveMemberIds(UUID conversationId) {
        return jdbc.sql("""
                        SELECT user_id
                        FROM conversation_members
                        WHERE conversation_id = :conversationId
                          AND status = 'ACTIVE'
                        ORDER BY user_id
                        """)
                .param("conversationId", conversationId)
                .query(UUID.class)
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
                row.getObject("media_id", UUID.class),
                row.getObject("server_accepted_at", OffsetDateTime.class).toInstant(),
                nullableInstant(row, "recalled_at"));
    }

    private static Instant nullableInstant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    record ConversationTarget(UUID conversationId, UUID peerUserId, String status) {
    }

    record GroupConversationTarget(
            UUID conversationId,
            String status,
            long historyVisibleAfterSeq
    ) {
    }
}
