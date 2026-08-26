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
                        SELECT id, account_no, display_name, avatar_media_id, avatar_version
                        FROM users
                        WHERE account_no = :accountNo
                          AND status = 'ACTIVE'
                          AND searchable_by_account_no = TRUE
                        """)
                .param("accountNo", accountNo)
                .query((row, rowNum) -> new ContactUser(
                        row.getObject("id", UUID.class),
                        trimNullable(row.getString("account_no")),
                        row.getString("display_name"),
                        row.getObject("avatar_media_id", UUID.class),
                        row.getLong("avatar_version")))
                .optional()
                .orElse(null);
    }

    ContactUser findActiveUser(UUID userId) {
        return jdbc.sql("""
                        SELECT id, account_no, display_name, avatar_media_id, avatar_version
                        FROM users
                        WHERE id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .query((row, rowNum) -> new ContactUser(
                        row.getObject("id", UUID.class),
                        row.getString("account_no").trim(),
                        row.getString("display_name"),
                        row.getObject("avatar_media_id", UUID.class),
                        row.getLong("avatar_version")))
                .optional()
                .orElse(null);
    }

    ContactUser findActiveUserByAccountNo(String accountNo) {
        return jdbc.sql("""
                        SELECT id, account_no, display_name, avatar_media_id, avatar_version
                        FROM users
                        WHERE account_no = :accountNo AND status = 'ACTIVE'
                        """)
                .param("accountNo", accountNo)
                .query((row, rowNum) -> new ContactUser(
                        row.getObject("id", UUID.class),
                        row.getString("account_no").trim(),
                        row.getString("display_name"),
                        row.getObject("avatar_media_id", UUID.class),
                        row.getLong("avatar_version")))
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
                        trimNullable(row.getString("peer_account_no")),
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
        snapshotReadonlyProfile(pair);
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

    private void snapshotReadonlyProfile(UserPair pair) {
        jdbc.sql("""
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
                        FROM users low
                        JOIN users high ON high.id = :highId
                        WHERE conversation.user_low_id = :lowId
                          AND conversation.user_high_id = :highId
                          AND low.id = :lowId
                        """)
                .param("lowId", pair.low())
                .param("highId", pair.high())
                .update();
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
                        u.account_no, u.display_name, u.avatar_media_id,
                               u.avatar_version, cc.conversation_id
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
                        "ACTIVE",
                        avatarUrl(
                                row.getObject("peer_id", UUID.class),
                                row.getObject("avatar_media_id", UUID.class),
                                row.getLong("avatar_version")),
                        row.getLong("avatar_version"),
                        fallback(row.getString("display_name"))))
                .list();
    }

    List<ConversationSummary> listConversations(UUID userId) {
        return jdbc.sql("""
                        SELECT cc.conversation_id,
                               CASE WHEN cc.user_low_id = :userId THEN cc.user_high_id ELSE cc.user_low_id END AS peer_id,
                               CASE WHEN u.status = 'DELETED' THEN NULL ELSE u.account_no END AS account_no,
                               CASE WHEN c.status = 'ACTIVE' THEN u.display_name
                                    WHEN cc.user_low_id = :userId THEN cc.readonly_high_display_name
                                    ELSE cc.readonly_low_display_name END AS display_name,
                               CASE WHEN c.status = 'ACTIVE' THEN NULL
                                    WHEN cc.user_low_id = :userId THEN cc.readonly_high_avatar_fallback
                                    ELSE cc.readonly_low_avatar_fallback END AS avatar_fallback,
                               u.avatar_media_id,
                               u.avatar_version, c.status,
                               COALESCE(my_read.read_seq, 0) AS read_seq,
                               COALESCE(peer_read.read_seq, 0) AS peer_read_seq,
                               COALESCE(unread.unread_count, 0) AS unread_count,
                               latest.conversation_seq AS latest_conversation_seq,
                               latest.type AS latest_type,
                               latest.state AS latest_state,
                               latest.text_content AS latest_text,
                               latest.server_accepted_at AS latest_server_accepted_at,
                               latest.system_event_type AS latest_system_event_type,
                               TRUE AS search_visible,
                               0 AS search_visible_after_seq,
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
                        LEFT JOIN conversation_read_states my_read
                          ON my_read.conversation_id = cc.conversation_id
                         AND my_read.user_id = :userId
                        LEFT JOIN conversation_read_states peer_read
                          ON peer_read.conversation_id = cc.conversation_id
                         AND peer_read.user_id = CASE
                             WHEN cc.user_low_id = :userId THEN cc.user_high_id
                             ELSE cc.user_low_id
                         END
                        LEFT JOIN LATERAL (
                            SELECT m.conversation_seq,
                                   m.type,
                                   m.state,
                                   m.text_content,
                                   m.server_accepted_at,
                                   m.system_event_type
                            FROM messages m
                            WHERE m.conversation_id = cc.conversation_id
                            ORDER BY m.conversation_seq DESC
                            LIMIT 1
                        ) latest ON TRUE
                        LEFT JOIN LATERAL (
                            SELECT COUNT(*) AS unread_count
                            FROM messages m
                            WHERE m.conversation_id = cc.conversation_id
                              AND m.sender_id <> :userId
                              AND m.type <> 'SYSTEM'
                              AND m.state = 'ACTIVE'
                              AND m.conversation_seq > COALESCE(my_read.read_seq, 0)
                        ) unread ON TRUE
                        WHERE cc.user_low_id = :userId OR cc.user_high_id = :userId
                        ORDER BY c.created_at DESC
                        """)
                .param("userId", userId)
                .query((row, rowNum) -> new ConversationSummary(
                        1,
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("peer_id", UUID.class),
                        trimNullable(row.getString("account_no")),
                        row.getString("display_name"),
                        row.getString("status"),
                        row.getString("relationship"),
                        row.getBoolean("blocked_by_me"),
                        row.getLong("read_seq"),
                        row.getLong("peer_read_seq"),
                        "ACTIVE".equals(row.getString("status"))
                                ? avatarUrl(
                                        row.getObject("peer_id", UUID.class),
                                        row.getObject("avatar_media_id", UUID.class),
                                        row.getLong("avatar_version"))
                                : null,
                        "ACTIVE".equals(row.getString("status"))
                                ? row.getLong("avatar_version")
                                : 0,
                        "ACTIVE".equals(row.getString("status"))
                                ? fallback(row.getString("display_name"))
                                : java.util.Objects.requireNonNullElse(
                                        row.getString("avatar_fallback"),
                                        fallback(row.getString("display_name"))),
                        row.getBoolean("search_visible"),
                        row.getLong("search_visible_after_seq"),
                        row.getLong("unread_count"),
                        row.getObject("latest_conversation_seq") == null
                                ? null
                                : new ConversationLatestMessage(
                                        row.getLong("latest_conversation_seq"),
                                        row.getString("latest_type"),
                                        row.getString("latest_state"),
                                        row.getString("latest_text"),
                                        instant(row.getObject("latest_server_accepted_at", OffsetDateTime.class)),
                                        row.getString("latest_system_event_type"))))
                .list();
    }

    private String avatarUrl(UUID userId, UUID avatarMediaId, long avatarVersion) {
        return avatarMediaId == null || avatarVersion == 0
                ? null
                : "/api/v1/users/" + userId
                + "/avatar?variant=thumb&avatarVersion=" + avatarVersion;
    }

    private String fallback(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "?";
        }
        int codePoint = displayName.codePointAt(0);
        return new String(Character.toChars(codePoint));
    }

    private String trimNullable(String value) {
        return value == null ? null : value.trim();
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

    record ContactUser(
            UUID id,
            String accountNo,
            String displayName,
            UUID avatarMediaId,
            long avatarVersion
    ) {
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
