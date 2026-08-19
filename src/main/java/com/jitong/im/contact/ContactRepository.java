package com.jitong.im.contact;

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
class ContactRepository {

    private final JdbcClient jdbc;

    ContactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    ContactUser findSearchableUser(String accountNo) {
        return jdbc.sql("""
                        SELECT id, account_no, display_name
                        FROM users
                        WHERE account_no = :accountNo
                          AND status = 'ACTIVE'
                          AND searchable_by_account_no = TRUE
                        """)
                .param("accountNo", accountNo)
                .query((row, rowNum) -> new ContactUser(
                        row.getObject("id", UUID.class),
                        row.getString("account_no").trim(),
                        row.getString("display_name")))
                .optional()
                .orElse(null);
    }

    ContactUser findActiveUser(UUID userId) {
        return jdbc.sql("""
                        SELECT id, account_no, display_name
                        FROM users
                        WHERE id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .query((row, rowNum) -> new ContactUser(
                        row.getObject("id", UUID.class),
                        row.getString("account_no").trim(),
                        row.getString("display_name")))
                .optional()
                .orElse(null);
    }

    ContactUser findActiveUserByAccountNo(String accountNo) {
        return jdbc.sql("""
                        SELECT id, account_no, display_name
                        FROM users
                        WHERE account_no = :accountNo AND status = 'ACTIVE'
                        """)
                .param("accountNo", accountNo)
                .query((row, rowNum) -> new ContactUser(
                        row.getObject("id", UUID.class),
                        row.getString("account_no").trim(),
                        row.getString("display_name")))
                .optional()
                .orElse(null);
    }

    ContactRequestRecord findRequest(UUID requestId) {
        return jdbc.sql("""
                        SELECT id, requester_id, recipient_id, verification, status,
                               expires_at, resolved_at
                        FROM contact_requests
                        WHERE id = :requestId
                        """)
                .param("requestId", requestId)
                .query(this::mapRequest)
                .optional()
                .orElse(null);
    }

    ContactRequestRecord findPendingRequest(UUID requesterId, UUID recipientId) {
        return jdbc.sql("""
                        SELECT id, requester_id, recipient_id, verification, status,
                               expires_at, resolved_at
                        FROM contact_requests
                        WHERE requester_id = :requesterId
                          AND recipient_id = :recipientId
                          AND status = 'PENDING'
                        FOR UPDATE
                        """)
                .param("requesterId", requesterId)
                .param("recipientId", recipientId)
                .query(this::mapRequest)
                .optional()
                .orElse(null);
    }

    ContactRequestRecord findPendingRequestBetween(UUID firstUserId, UUID secondUserId) {
        return jdbc.sql("""
                        SELECT id, requester_id, recipient_id, verification, status,
                               expires_at, resolved_at
                        FROM contact_requests
                        WHERE status = 'PENDING'
                          AND ((requester_id = :firstUserId AND recipient_id = :secondUserId)
                            OR (requester_id = :secondUserId AND recipient_id = :firstUserId))
                        ORDER BY created_at ASC
                        LIMIT 1
                        """)
                .param("firstUserId", firstUserId)
                .param("secondUserId", secondUserId)
                .query(this::mapRequest)
                .optional()
                .orElse(null);
    }

    void updateSearchability(UUID userId, boolean searchable) {
        jdbc.sql("""
                        UPDATE users
                        SET searchable_by_account_no = :searchable
                        WHERE id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("searchable", searchable)
                .update();
    }

    List<ContactRequestRecord> findPendingRequestsBetween(UUID firstUserId, UUID secondUserId) {
        return jdbc.sql("""
                        SELECT id, requester_id, recipient_id, verification, status,
                               expires_at, resolved_at
                        FROM contact_requests
                        WHERE status = 'PENDING'
                          AND ((requester_id = :firstUserId AND recipient_id = :secondUserId)
                            OR (requester_id = :secondUserId AND recipient_id = :firstUserId))
                        ORDER BY created_at ASC
                        FOR UPDATE
                        """)
                .param("firstUserId", firstUserId)
                .param("secondUserId", secondUserId)
                .query(this::mapRequest)
                .list();
    }

    List<ContactRequestSummary> listRequests(UUID userId) {
        return jdbc.sql("""
                        SELECT r.id, r.requester_id, r.recipient_id, r.verification,
                               r.status, r.expires_at,
                               peer.account_no AS peer_account_no,
                               peer.display_name AS peer_display_name
                        FROM contact_requests r
                        JOIN users peer
                          ON peer.id = CASE
                               WHEN r.requester_id = :userId THEN r.recipient_id
                               ELSE r.requester_id
                             END
                        WHERE r.requester_id = :userId OR r.recipient_id = :userId
                        ORDER BY r.created_at DESC
                        """)
                .param("userId", userId)
                .query((row, rowNum) -> new ContactRequestSummary(
                        1,
                        row.getObject("id", UUID.class),
                        row.getObject("requester_id", UUID.class),
                        row.getObject("recipient_id", UUID.class),
                        row.getString("status"),
                        row.getString("verification"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        userId.equals(row.getObject("recipient_id", UUID.class)),
                        row.getString("peer_account_no").trim(),
                        row.getString("peer_display_name")))
                .list();
    }

    ContactRecord findContact(UUID firstUserId, UUID secondUserId) {
        UserPair pair = UserPair.of(firstUserId, secondUserId);
        return jdbc.sql("""
                        SELECT user_low_id, user_high_id, status, removed_at
                        FROM contacts
                        WHERE user_low_id = :userLowId AND user_high_id = :userHighId
                        """)
                .param("userLowId", pair.low())
                .param("userHighId", pair.high())
                .query((row, rowNum) -> new ContactRecord(
                        row.getObject("user_low_id", UUID.class),
                        row.getObject("user_high_id", UUID.class),
                        row.getString("status"),
                        instant(row.getObject("removed_at", OffsetDateTime.class))))
                .optional()
                .orElse(null);
    }

    boolean isBlocked(UUID blockerId, UUID blockedId) {
        return jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM user_blocks
                            WHERE blocker_id = :blockerId AND blocked_id = :blockedId)
                        """)
                .param("blockerId", blockerId)
                .param("blockedId", blockedId)
                .query(Boolean.class)
                .single();
    }

    ContactRequestRecord insertRequest(UUID requestId, UUID requesterId, UUID recipientId,
                                       String verification, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO contact_requests (
                            id, requester_id, recipient_id, verification, expires_at)
                        VALUES (:id, :requesterId, :recipientId, :verification, :expiresAt)
                        """)
                .param("id", requestId)
                .param("requesterId", requesterId)
                .param("recipientId", recipientId)
                .param("verification", verification)
                .param("expiresAt", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return findRequest(requestId);
    }

    void updateRequestStatus(UUID requestId, String status, Instant resolvedAt) {
        jdbc.sql("""
                        UPDATE contact_requests
                        SET status = :status, resolved_at = :resolvedAt
                        WHERE id = :requestId AND status = 'PENDING'
                        """)
                .param("requestId", requestId)
                .param("status", status)
                .param("resolvedAt", utc(resolvedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    ContactRequestRecord findPendingRequestForUpdate(UUID requestId) {
        return jdbc.sql("""
                        SELECT id, requester_id, recipient_id, verification, status,
                               expires_at, resolved_at
                        FROM contact_requests
                        WHERE id = :requestId
                        FOR UPDATE
                        """)
                .param("requestId", requestId)
                .query(this::mapRequest)
                .optional()
                .orElse(null);
    }

    void expirePendingRequests(Instant now) {
        jdbc.sql("""
                        UPDATE contact_requests
                        SET status = 'EXPIRED', resolved_at = :resolvedAt
                        WHERE status = 'PENDING' AND expires_at <= :now
                        """)
                .param("resolvedAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    void expirePendingRequestsBetween(UUID firstUserId, UUID secondUserId, Instant now) {
        jdbc.sql("""
                        UPDATE contact_requests
                        SET status = 'EXPIRED', resolved_at = :resolvedAt
                        WHERE status = 'PENDING'
                          AND expires_at <= :now
                          AND ((requester_id = :firstUserId AND recipient_id = :secondUserId)
                            OR (requester_id = :secondUserId AND recipient_id = :firstUserId))
                        """)
                .param("resolvedAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("firstUserId", firstUserId)
                .param("secondUserId", secondUserId)
                .update();
    }

    void lockUser(UUID userId) {
        jdbc.sql("""
                        SELECT id
                        FROM users
                        WHERE id = :userId
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .query(UUID.class)
                .single();
    }

    void upsertActiveContact(UUID firstUserId, UUID secondUserId) {
        UserPair pair = UserPair.of(firstUserId, secondUserId);
        jdbc.sql("""
                        INSERT INTO contacts (user_low_id, user_high_id, status, removed_at)
                        VALUES (:userLowId, :userHighId, 'ACTIVE', NULL)
                        ON CONFLICT (user_low_id, user_high_id)
                        DO UPDATE SET status = 'ACTIVE', removed_at = NULL
                        """)
                .param("userLowId", pair.low())
                .param("userHighId", pair.high())
                .update();
    }

    void removeContact(UUID firstUserId, UUID secondUserId, Instant now) {
        UserPair pair = UserPair.of(firstUserId, secondUserId);
        jdbc.sql("""
                        UPDATE contacts
                        SET status = 'REMOVED', removed_at = :removedAt
                        WHERE user_low_id = :userLowId AND user_high_id = :userHighId
                          AND status = 'ACTIVE'
                        """)
                .param("userLowId", pair.low())
                .param("userHighId", pair.high())
                .param("removedAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        markConversationReadOnly(firstUserId, secondUserId);
    }

    void insertBlock(UUID blockerId, UUID blockedId) {
        jdbc.sql("""
                        INSERT INTO user_blocks (blocker_id, blocked_id)
                        VALUES (:blockerId, :blockedId)
                        ON CONFLICT (blocker_id, blocked_id) DO NOTHING
                        """)
                .param("blockerId", blockerId)
                .param("blockedId", blockedId)
                .update();
    }

    void deleteBlock(UUID blockerId, UUID blockedId) {
        jdbc.sql("""
                        DELETE FROM user_blocks
                        WHERE blocker_id = :blockerId AND blocked_id = :blockedId
                        """)
                .param("blockerId", blockerId)
                .param("blockedId", blockedId)
                .update();
    }

    void cancelPendingRequests(UUID firstUserId, UUID secondUserId, Instant now) {
        jdbc.sql("""
                        UPDATE contact_requests
                        SET status = 'CANCELLED', resolved_at = :resolvedAt
                        WHERE status = 'PENDING'
                          AND ((requester_id = :firstUserId AND recipient_id = :secondUserId)
                            OR (requester_id = :secondUserId AND recipient_id = :firstUserId))
                        """)
                .param("firstUserId", firstUserId)
                .param("secondUserId", secondUserId)
                .param("resolvedAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    UUID findOrCreateConversation(UUID firstUserId, UUID secondUserId) {
        UserPair pair = UserPair.of(firstUserId, secondUserId);
        UUID conversationId = jdbc.sql("""
                        SELECT conversation_id
                        FROM c2c_conversations
                        WHERE user_low_id = :userLowId AND user_high_id = :userHighId
                        FOR UPDATE
                        """)
                .param("userLowId", pair.low())
                .param("userHighId", pair.high())
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (conversationId != null) {
            jdbc.sql("UPDATE conversations SET status = 'ACTIVE' WHERE id = :conversationId")
                    .param("conversationId", conversationId)
                    .update();
            return conversationId;
        }

        UUID newConversationId = UuidV7.random();
        jdbc.sql("""
                        INSERT INTO conversations (id, type, status)
                        VALUES (:id, 'C2C', 'ACTIVE')
                        """)
                .param("id", newConversationId)
                .update();
        jdbc.sql("""
                        INSERT INTO c2c_conversations (conversation_id, user_low_id, user_high_id)
                        VALUES (:conversationId, :userLowId, :userHighId)
                        """)
                .param("conversationId", newConversationId)
                .param("userLowId", pair.low())
                .param("userHighId", pair.high())
                .update();
        return newConversationId;
    }

    UUID findConversation(UUID firstUserId, UUID secondUserId) {
        UserPair pair = UserPair.of(firstUserId, secondUserId);
        return jdbc.sql("""
                        SELECT conversation_id
                        FROM c2c_conversations
                        WHERE user_low_id = :userLowId AND user_high_id = :userHighId
                        """)
                .param("userLowId", pair.low())
                .param("userHighId", pair.high())
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    List<ContactSummary> listContacts(UUID userId) {
        return jdbc.sql("""
                        SELECT CASE WHEN c.user_low_id = :userId THEN c.user_high_id ELSE c.user_low_id END AS peer_id,
                               u.account_no, u.display_name, cc.conversation_id
                        FROM contacts c
                        JOIN users u ON u.id = CASE WHEN c.user_low_id = :userId THEN c.user_high_id ELSE c.user_low_id END
                        JOIN c2c_conversations cc ON cc.user_low_id = c.user_low_id AND cc.user_high_id = c.user_high_id
                        WHERE (c.user_low_id = :userId OR c.user_high_id = :userId)
                          AND c.status = 'ACTIVE'
                        ORDER BY u.display_name, u.account_no
                        """)
                .param("userId", userId)
                .query((row, rowNum) -> new ContactSummary(
                        1,
                        row.getObject("peer_id", UUID.class),
                        row.getString("account_no").trim(),
                        row.getString("display_name"),
                        row.getObject("conversation_id", UUID.class),
                        "ACTIVE"))
                .list();
    }

    List<ConversationSummary> listConversations(UUID userId) {
        return jdbc.sql("""
                        SELECT cc.conversation_id,
                               CASE WHEN cc.user_low_id = :userId THEN cc.user_high_id ELSE cc.user_low_id END AS peer_id,
                               u.account_no, u.display_name, c.status,
                               CASE WHEN c.status = 'ACTIVE' THEN 'ACTIVE' ELSE 'READ_ONLY' END AS relationship,
                               EXISTS (
                                   SELECT 1
                                   FROM user_blocks b
                                   WHERE b.blocker_id = :userId
                                     AND b.blocked_id = CASE
                                         WHEN cc.user_low_id = :userId THEN cc.user_high_id
                                         ELSE cc.user_low_id
                                     END
                               ) AS blocked_by_me
                        FROM c2c_conversations cc
                        JOIN conversations c ON c.id = cc.conversation_id
                        JOIN users u ON u.id = CASE WHEN cc.user_low_id = :userId THEN cc.user_high_id ELSE cc.user_low_id END
                        WHERE cc.user_low_id = :userId OR cc.user_high_id = :userId
                        ORDER BY c.created_at DESC
                        """)
                .param("userId", userId)
                .query((row, rowNum) -> new ConversationSummary(
                        1,
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("peer_id", UUID.class),
                        row.getString("account_no").trim(),
                        row.getString("display_name"),
                        row.getString("status"),
                        row.getString("relationship"),
                        row.getBoolean("blocked_by_me")))
                .list();
    }

    private void markConversationReadOnly(UUID firstUserId, UUID secondUserId) {
        UUID conversationId = findConversation(firstUserId, secondUserId);
        if (conversationId != null) {
            jdbc.sql("UPDATE conversations SET status = 'READ_ONLY' WHERE id = :conversationId")
                    .param("conversationId", conversationId)
                    .update();
        }
    }

    private ContactRequestRecord mapRequest(java.sql.ResultSet row, int rowNum) throws java.sql.SQLException {
        return new ContactRequestRecord(
                row.getObject("id", UUID.class),
                row.getObject("requester_id", UUID.class),
                row.getObject("recipient_id", UUID.class),
                row.getString("verification"),
                row.getString("status"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                instant(row.getObject("resolved_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    record ContactUser(UUID id, String accountNo, String displayName) {
    }

    record ContactRequestRecord(
            UUID id,
            UUID requesterId,
            UUID recipientId,
            String verification,
            String status,
            Instant expiresAt,
            Instant resolvedAt
    ) {
    }

    record ContactRecord(UUID lowUserId, UUID highUserId, String status, Instant removedAt) {
    }

    record UserPair(UUID low, UUID high) {
        static UserPair of(UUID first, UUID second) {
            return first.compareTo(second) < 0
                    ? new UserPair(first, second)
                    : new UserPair(second, first);
        }
    }
}
