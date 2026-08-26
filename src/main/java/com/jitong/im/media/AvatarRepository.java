package com.jitong.im.media;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
class AvatarRepository {

    private final JdbcClient jdbc;

    AvatarRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    AvatarOwner findUserForUpdate(UUID userId) {
        return jdbc.sql("""
                        SELECT id, display_name, avatar_media_id, avatar_version
                        FROM users
                        WHERE id = :userId AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .query((row, rowNum) -> new AvatarOwner(
                        row.getObject("id", UUID.class),
                        row.getString("display_name"),
                        row.getObject("avatar_media_id", UUID.class),
                        row.getLong("avatar_version")))
                .optional()
                .orElse(null);
    }

    UserProfile findUserProfile(UUID userId) {
        return jdbc.sql("""
                        SELECT id, display_name, avatar_media_id, avatar_version, status
                        FROM users
                        WHERE id = :userId
                        """)
                .param("userId", userId)
                .query((row, rowNum) -> new UserProfile(
                        row.getObject("id", UUID.class),
                        row.getString("display_name"),
                        row.getObject("avatar_media_id", UUID.class),
                        row.getLong("avatar_version"),
                        row.getString("status")))
                .optional()
                .orElse(null);
    }

    AvatarMedia findAvatarUpload(UUID userId, UUID uploadId) {
        return jdbc.sql(avatarSelect() + """
                        WHERE m.uploader_id = :userId
                          AND m.upload_id = :uploadId
                          AND m.purpose = 'AVATAR'
                        """)
                .param("userId", userId)
                .param("uploadId", uploadId)
                .query(this::mapMedia)
                .optional()
                .orElse(null);
    }

    void replaceUserAvatar(UUID userId, UUID mediaId, long nextVersion, Instant now) {
        jdbc.sql("""
                        UPDATE users
                        SET avatar_media_id = :mediaId,
                            avatar_version = :avatarVersion
                        WHERE id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("mediaId", mediaId)
                .param("avatarVersion", nextVersion)
                .update();
    }

    void removeUserAvatar(UUID userId, long nextVersion) {
        jdbc.sql("""
                        UPDATE users
                        SET avatar_media_id = NULL,
                            avatar_version = :avatarVersion
                        WHERE id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("avatarVersion", nextVersion)
                .update();
    }

    void updateUserDisplayName(UUID userId, String displayName) {
        jdbc.sql("""
                        UPDATE users
                        SET display_name = :displayName
                        WHERE id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("displayName", displayName)
                .update();
    }

    AvatarMedia findCurrentUserAvatar(UUID userId) {
        return jdbc.sql(avatarSelect() + """
                        WHERE m.id = (
                            SELECT avatar_media_id
                            FROM users
                            WHERE id = :userId
                        )
                          AND m.state = 'BOUND'
                        """)
                .param("userId", userId)
                .query(this::mapMedia)
                .optional()
                .orElse(null);
    }

    boolean hasC2cAccess(UUID requesterId, UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM c2c_conversations c
                            JOIN conversations conversation ON conversation.id = c.conversation_id
                            WHERE conversation.status = 'ACTIVE'
                              AND (
                                  (c.user_low_id = :requesterId AND c.user_high_id = :userId)
                                  OR (c.user_low_id = :userId AND c.user_high_id = :requesterId)
                              )
                        )
                        """)
                .param("requesterId", requesterId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    boolean hasHistoryAccess(UUID requesterId, UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM conversations conversation
                            JOIN c2c_conversations c2c
                              ON c2c.conversation_id = conversation.id
                            WHERE conversation.status IN ('ACTIVE', 'READ_ONLY')
                              AND (
                                  (c2c.user_low_id = :requesterId AND c2c.user_high_id = :userId)
                                  OR (c2c.user_low_id = :userId AND c2c.user_high_id = :requesterId)
                              )
                        )
                        OR EXISTS (
                            SELECT 1
                            FROM messages message
                            JOIN conversation_members member
                              ON member.conversation_id = message.conversation_id
                             AND member.user_id = :requesterId
                             AND member.status = 'ACTIVE'
                            JOIN groups group_chat
                              ON group_chat.conversation_id = message.conversation_id
                             AND group_chat.status = 'ACTIVE'
                            WHERE message.sender_id = :userId
                              AND message.state IN ('ACTIVE', 'RECALLED', 'MODERATED')
                              AND message.conversation_seq > member.history_visible_after_seq
                        )
                        """)
                .param("requesterId", requesterId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    java.util.List<UUID> profileEventRecipients(UUID userId) {
        return jdbc.sql("""
                        SELECT :userId
                        UNION
                        SELECT CASE WHEN c.user_low_id = :userId
                            THEN c.user_high_id ELSE c.user_low_id END
                        FROM contacts c
                        JOIN c2c_conversations c2c
                          ON c2c.user_low_id = c.user_low_id
                         AND c2c.user_high_id = c.user_high_id
                        JOIN conversations conversation
                          ON conversation.id = c2c.conversation_id
                        WHERE (c.user_low_id = :userId OR c.user_high_id = :userId)
                          AND c.status = 'ACTIVE'
                          AND conversation.status = 'ACTIVE'
                        ORDER BY 1
                        """)
                .param("userId", userId)
                .query(UUID.class)
                .list();
    }

    GroupOwner findGroupForUpdate(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT g.conversation_id, g.owner_user_id, g.avatar_media_id, g.avatar_version
                        FROM groups g
                        JOIN conversation_members member
                          ON member.conversation_id = g.conversation_id
                         AND member.user_id = :userId
                         AND member.status = 'ACTIVE'
                         AND member.role IN ('OWNER', 'ADMIN')
                        WHERE g.conversation_id = :conversationId
                          AND g.status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query((row, rowNum) -> new GroupOwner(
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("owner_user_id", UUID.class),
                        row.getObject("avatar_media_id", UUID.class),
                        row.getLong("avatar_version")))
                .optional()
                .orElse(null);
    }

    void replaceGroupAvatar(UUID conversationId, UUID mediaId, long nextVersion) {
        jdbc.sql("""
                        UPDATE groups
                        SET avatar_media_id = :mediaId,
                            avatar_version = :avatarVersion
                        WHERE conversation_id = :conversationId
                          AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("mediaId", mediaId)
                .param("avatarVersion", nextVersion)
                .update();
    }

    void removeGroupAvatar(UUID conversationId, long nextVersion) {
        jdbc.sql("""
                        UPDATE groups
                        SET avatar_media_id = NULL,
                            avatar_version = :avatarVersion
                        WHERE conversation_id = :conversationId
                          AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("avatarVersion", nextVersion)
                .update();
    }

    GroupProfile findGroupProfile(UUID conversationId) {
        return jdbc.sql("""
                        SELECT conversation_id, avatar_media_id, avatar_version
                        FROM groups
                        WHERE conversation_id = :conversationId AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .query((row, rowNum) -> new GroupProfile(
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("avatar_media_id", UUID.class),
                        row.getLong("avatar_version")))
                .optional()
                .orElse(null);
    }

    java.util.List<UUID> activeGroupMemberIds(UUID conversationId) {
        return jdbc.sql("""
                        SELECT user_id
                        FROM conversation_members
                        WHERE conversation_id = :conversationId AND status = 'ACTIVE'
                        ORDER BY user_id
                        """)
                .param("conversationId", conversationId)
                .query(UUID.class)
                .list();
    }

    boolean isActiveGroupMember(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM conversation_members
                            WHERE conversation_id = :conversationId
                              AND user_id = :userId
                              AND status = 'ACTIVE'
                        )
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    boolean isDiscoverableGroup(UUID conversationId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM groups
                            WHERE conversation_id = :conversationId
                              AND status = 'ACTIVE'
                              AND visibility IN ('PUBLIC', 'UNLISTED')
                        )
                        """)
                .param("conversationId", conversationId)
                .query(Boolean.class)
                .single();
    }

    private String avatarSelect() {
        return """
                SELECT m.id, m.state, m.original_object_key, m.thumbnail_object_key,
                       m.content_type, m.width, m.height, m.byte_size,
                       m.created_at, m.bound_at, m.expired_at, m.objects_deleted_at,
                       m.sha256, m.attached_entity_id, m.attached_entity_type
                FROM media m
                """;
    }

    private AvatarMedia mapMedia(java.sql.ResultSet row, int rowNum) throws java.sql.SQLException {
        return new AvatarMedia(
                row.getObject("id", UUID.class),
                row.getString("state"),
                row.getString("original_object_key"),
                row.getString("thumbnail_object_key"),
                row.getString("content_type"),
                row.getInt("width"),
                row.getInt("height"),
                row.getLong("byte_size"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                nullableInstant(row, "bound_at"),
                nullableInstant(row, "expired_at"),
                nullableInstant(row, "objects_deleted_at"),
                row.getString("sha256"));
    }

    private static Instant nullableInstant(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record AvatarOwner(
            UUID userId,
            String displayName,
            UUID avatarMediaId,
            long avatarVersion
    ) {
        AvatarOwner(UUID userId, UUID avatarMediaId, long avatarVersion) {
            this(userId, null, avatarMediaId, avatarVersion);
        }
    }

    record UserProfile(
            UUID userId,
            String displayName,
            UUID avatarMediaId,
            long avatarVersion,
            String status
    ) {
        UserProfile(
                UUID userId,
                String displayName,
                UUID avatarMediaId,
                long avatarVersion
        ) {
                this(userId, displayName, avatarMediaId, avatarVersion, "ACTIVE");
        }
    }

    record GroupOwner(
            UUID conversationId,
            UUID ownerUserId,
            UUID avatarMediaId,
            long avatarVersion
    ) {
    }

    record GroupProfile(
            UUID conversationId,
            UUID avatarMediaId,
            long avatarVersion
    ) {
    }

    record AvatarMedia(
            UUID mediaId,
            String state,
            String originalObjectKey,
            String thumbnailObjectKey,
            String contentType,
            int width,
            int height,
            long byteSize,
            Instant createdAt,
            Instant boundAt,
            Instant expiredAt,
            Instant objectsDeletedAt,
            String sha256
    ) {
    }
}
