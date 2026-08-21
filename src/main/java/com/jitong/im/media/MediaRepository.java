package com.jitong.im.media;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class MediaRepository {

    private final JdbcClient jdbc;

    MediaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    MediaRecord findByUploadId(UUID uploaderId, UUID uploadId) {
        return jdbc.sql(selectSql() + """
                        WHERE uploader_id = :uploaderId AND upload_id = :uploadId
                        """)
                .param("uploaderId", uploaderId)
                .param("uploadId", uploadId)
                .query(this::map)
                .optional()
                .orElse(null);
    }

    MediaRecord insertTemp(
            UUID mediaId,
            UUID uploaderId,
            UUID uploadId,
            String originalObjectKey,
            String thumbnailObjectKey,
            String contentType,
            int width,
            int height,
            long byteSize,
            String sha256,
            Instant createdAt
    ) {
        return insertTemp(
                mediaId,
                "MESSAGE_IMAGE",
                uploaderId,
                uploadId,
                originalObjectKey,
                thumbnailObjectKey,
                contentType,
                width,
                height,
                byteSize,
                sha256,
                null,
                null,
                createdAt);
    }

    MediaRecord insertTemp(
            UUID mediaId,
            String purpose,
            UUID uploaderId,
            UUID uploadId,
            String originalObjectKey,
            String thumbnailObjectKey,
            String contentType,
            int width,
            int height,
            long byteSize,
            String sha256,
            UUID attachedEntityId,
            String attachedEntityType,
            Instant createdAt
    ) {
        jdbc.sql("""
                        INSERT INTO media (
                            id, purpose, uploader_id, upload_id, state,
                            original_object_key, thumbnail_object_key, content_type,
                            width, height, byte_size, sha256, attached_entity_id,
                            attached_entity_type, created_at
                        ) VALUES (
                            :id, :purpose, :uploaderId, :uploadId, 'TEMP',
                            :originalObjectKey, :thumbnailObjectKey, :contentType,
                            :width, :height, :byteSize, :sha256, :attachedEntityId,
                            :attachedEntityType, :createdAt
                        )
                        """)
                .param("id", mediaId)
                .param("purpose", purpose)
                .param("uploaderId", uploaderId)
                .param("uploadId", uploadId)
                .param("originalObjectKey", originalObjectKey)
                .param("thumbnailObjectKey", thumbnailObjectKey)
                .param("contentType", contentType)
                .param("width", width)
                .param("height", height)
                .param("byteSize", byteSize)
                .param("sha256", sha256)
                .param("attachedEntityId", attachedEntityId, Types.OTHER)
                .param("attachedEntityType", attachedEntityType, Types.VARCHAR)
                .param("createdAt", utc(createdAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return findById(mediaId);
    }

    MediaRecord findById(UUID mediaId) {
        return jdbc.sql(selectSql() + """
                        WHERE id = :mediaId
                        """)
                .param("mediaId", mediaId)
                .query(this::map)
                .optional()
                .orElse(null);
    }

    MediaRecord findByIdForUpdate(UUID mediaId) {
        return jdbc.sql(selectSql() + """
                        WHERE id = :mediaId
                        FOR UPDATE
                        """)
                .param("mediaId", mediaId)
                .query(this::map)
                .optional()
                .orElse(null);
    }

    boolean bindToMessage(UUID mediaId, UUID uploaderId, UUID messageId, Instant boundAt) {
        return jdbc.sql("""
                        UPDATE media
                        SET state = 'BOUND',
                            attached_message_id = :messageId,
                            bound_at = :boundAt
                        WHERE id = :mediaId
                          AND uploader_id = :uploaderId
                          AND state = 'TEMP'
                        """)
                .param("mediaId", mediaId)
                .param("uploaderId", uploaderId)
                .param("messageId", messageId)
                .param("boundAt", utc(boundAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update() == 1;
    }

    boolean bindToAvatar(
            UUID mediaId,
            UUID uploaderId,
            String entityType,
            UUID entityId,
            Instant boundAt
    ) {
        return jdbc.sql("""
                        UPDATE media
                        SET state = 'BOUND',
                            attached_message_id = NULL,
                            attached_entity_id = :entityId,
                            attached_entity_type = :entityType,
                            bound_at = :boundAt
                        WHERE id = :mediaId
                          AND uploader_id = :uploaderId
                          AND purpose = 'AVATAR'
                          AND state = 'TEMP'
                          AND attached_message_id IS NULL
                          AND attached_entity_id IS NULL
                          AND attached_entity_type IS NULL
                          AND bound_at IS NULL
                        """)
                .param("mediaId", mediaId)
                .param("uploaderId", uploaderId)
                .param("entityId", entityId)
                .param("entityType", entityType)
                .param("boundAt", utc(boundAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update() == 1;
    }

    boolean expireAvatar(UUID mediaId, String entityType, UUID entityId, Instant expiredAt) {
        return jdbc.sql("""
                        UPDATE media
                        SET state = 'EXPIRED',
                            expired_at = COALESCE(expired_at, :expiredAt),
                            attached_entity_id = NULL,
                            attached_entity_type = NULL,
                            bound_at = NULL
                        WHERE id = :mediaId
                          AND purpose = 'AVATAR'
                          AND attached_entity_id = :entityId
                          AND attached_entity_type = :entityType
                          AND state = 'BOUND'
                        """)
                .param("mediaId", mediaId)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("expiredAt", utc(expiredAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update() == 1;
    }

    void expireMediaForMessage(UUID messageId, Instant expiredAt) {
        jdbc.sql("""
                        UPDATE media
                        SET state = 'EXPIRED',
                            expired_at = COALESCE(expired_at, :expiredAt),
                            attached_message_id = NULL,
                            bound_at = NULL
                        WHERE attached_message_id = :messageId
                          AND state = 'BOUND'
                        """)
                .param("messageId", messageId)
                .param("expiredAt", utc(expiredAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    AccessRecord findAccess(UUID mediaId, UUID userId) {
        return jdbc.sql("""
                        SELECT m.id, m.state, m.original_object_key, m.thumbnail_object_key,
                               m.content_type, m.uploader_id,
                               (
                                   m.state = 'EXPIRED'
                                   OR (
                                       m.state = 'TEMP'
                                       AND m.created_at <= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                                   )
                               ) AS expired,
                               (
                                   (m.state = 'TEMP' AND m.uploader_id = :userId)
                                   OR (m.state = 'BOUND' AND EXISTS (
                                       SELECT 1
                                       FROM messages message
                                       JOIN c2c_conversations conversation
                                         ON conversation.conversation_id = message.conversation_id
                                       WHERE message.media_id = m.id
                                         AND message.state = 'ACTIVE'
                                         AND (
                                             conversation.user_low_id = :userId
                                             OR conversation.user_high_id = :userId
                                         )
                                   ))
                               ) AS permitted
                        FROM media m
                        WHERE m.id = :mediaId
                        """)
                .param("mediaId", mediaId)
                .param("userId", userId)
                .query((row, rowNum) -> new AccessRecord(
                        row.getObject("id", UUID.class),
                        row.getString("state"),
                        row.getString("original_object_key"),
                        row.getString("thumbnail_object_key"),
                        row.getString("content_type"),
                        row.getObject("uploader_id", UUID.class),
                        row.getBoolean("expired"),
                        row.getBoolean("permitted")))
                .optional()
                .orElse(null);
    }

    List<MediaRecord> findCleanupCandidates(Instant cutoff) {
        return jdbc.sql(selectSql() + """
                        WHERE (state = 'TEMP' AND created_at < :cutoff)
                           OR (state = 'EXPIRED' AND objects_deleted_at IS NULL)
                        ORDER BY created_at ASC
                        LIMIT 100
                        """)
                .param("cutoff", utc(cutoff), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(this::map)
                .list();
    }

    void markExpired(UUID mediaId, Instant expiredAt) {
        jdbc.sql("""
                        UPDATE media
                        SET state = 'EXPIRED', expired_at = COALESCE(expired_at, :expiredAt)
                        WHERE id = :mediaId AND state = 'TEMP'
                        """)
                .param("mediaId", mediaId)
                .param("expiredAt", utc(expiredAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    void markObjectsDeleted(UUID mediaId, Instant deletedAt) {
        jdbc.sql("""
                        UPDATE media
                        SET objects_deleted_at = :deletedAt
                        WHERE id = :mediaId AND state = 'EXPIRED'
                        """)
                .param("mediaId", mediaId)
                .param("deletedAt", utc(deletedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private String selectSql() {
        return """
                        SELECT id, purpose, uploader_id, upload_id, state,
                               original_object_key, thumbnail_object_key, content_type,
                               width, height, byte_size, sha256, attached_message_id,
                               created_at, bound_at, expired_at, objects_deleted_at,
                               attached_entity_id, attached_entity_type
                        FROM media
                        """;
    }

    private MediaRecord map(java.sql.ResultSet row, int rowNum) throws java.sql.SQLException {
        return new MediaRecord(
                row.getObject("id", UUID.class),
                row.getString("purpose"),
                row.getObject("uploader_id", UUID.class),
                row.getObject("upload_id", UUID.class),
                row.getString("state"),
                row.getString("original_object_key"),
                row.getString("thumbnail_object_key"),
                row.getString("content_type"),
                row.getInt("width"),
                row.getInt("height"),
                row.getLong("byte_size"),
                row.getString("sha256"),
                row.getObject("attached_message_id", UUID.class),
                instant(row, "created_at"),
                nullableInstant(row, "bound_at"),
                nullableInstant(row, "expired_at"),
                nullableInstant(row, "objects_deleted_at"),
                row.getObject("attached_entity_id", UUID.class),
                row.getString("attached_entity_type"));
    }

    private static Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    record AccessRecord(
            UUID mediaId,
            String state,
            String originalObjectKey,
            String thumbnailObjectKey,
            String contentType,
            UUID uploaderId,
            boolean expired,
            boolean permitted
    ) {
    }
}
