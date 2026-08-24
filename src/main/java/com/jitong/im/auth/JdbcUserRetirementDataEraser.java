package com.jitong.im.auth;

import com.jitong.im.auth.PrivateAiDataEraser;
import com.jitong.im.media.MediaStorage;
import com.jitong.im.sync.SyncService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * Removes the retired user's private relationships while keeping durable
 * conversations and messages needed by other participants.
 */
@Service
class JdbcUserRetirementDataEraser implements UserRetirementDataEraser {

    private static final String DELETED_DISPLAY_NAME = "已注销用户";
    private static final String DELETED_AVATAR_FALLBACK = "已";

    private final JdbcClient jdbc;
    private final PrivateAiDataEraser privateAiDataEraser;
    private final SyncService syncService;
    private final MediaStorage mediaStorage;

    JdbcUserRetirementDataEraser(
            JdbcClient jdbc,
            PrivateAiDataEraser privateAiDataEraser,
            SyncService syncService,
            MediaStorage mediaStorage
    ) {
        this.jdbc = jdbc;
        this.privateAiDataEraser = privateAiDataEraser;
        this.syncService = syncService;
        this.mediaStorage = mediaStorage;
    }

    @Override
    @Transactional
    public void eraseForRetirement(UUID userId) {
        privateAiDataEraser.eraseForRetirement(userId);
        List<UUID> profileRecipients = activeProfileRecipients(userId);
        List<MediaObject> avatarObjects = avatarObjects(userId);

        updateReadonlyC2cHistory(userId);
        deletePrivateRelationships(userId);
        expireAvatarRows(userId);
        anonymizeUser(userId);
        if (!profileRecipients.isEmpty()) {
            syncService.recordEventForUsers(
                    profileRecipients,
                    "USER_PROFILE_UPDATED",
                    userId,
                    null);
        }
        registerAvatarObjectCleanup(avatarObjects);
    }

    private List<UUID> activeProfileRecipients(UUID userId) {
        return jdbc.sql("""
                        SELECT DISTINCT recipient_id
                        FROM (
                            SELECT CASE
                                       WHEN c.user_low_id = :userId THEN c.user_high_id
                                       ELSE c.user_low_id
                                   END AS recipient_id
                            FROM c2c_conversations c
                            WHERE c.user_low_id = :userId OR c.user_high_id = :userId
                            UNION ALL
                            SELECT member.user_id
                            FROM conversation_members member
                            JOIN conversation_members retired_member
                              ON retired_member.conversation_id = member.conversation_id
                             AND retired_member.user_id = :userId
                            WHERE member.status = 'ACTIVE'
                              AND member.user_id <> :userId
                        ) recipients
                        JOIN users recipient ON recipient.id = recipients.recipient_id
                        WHERE recipients.recipient_id <> :userId
                          AND recipient.status = 'ACTIVE'
                        ORDER BY recipient_id
                        """)
                .param("userId", userId)
                .query(UUID.class)
                .list();
    }

    private List<MediaObject> avatarObjects(UUID userId) {
        return jdbc.sql("""
                        SELECT id, original_object_key, thumbnail_object_key
                        FROM media
                        WHERE uploader_id = :userId
                          AND purpose = 'AVATAR'
                        ORDER BY id
                        """)
                .param("userId", userId)
                .query((row, rowNum) -> new MediaObject(
                        row.getObject("id", UUID.class),
                        row.getString("original_object_key"),
                        row.getString("thumbnail_object_key")))
                .list();
    }

    private void updateReadonlyC2cHistory(UUID userId) {
        jdbc.sql("""
                        UPDATE c2c_conversations conversation
                        SET readonly_low_display_name = CASE
                                WHEN conversation.user_low_id = :userId THEN :deletedDisplayName
                                ELSE low.display_name
                            END,
                            readonly_low_avatar_fallback = CASE
                                WHEN conversation.user_low_id = :userId THEN :deletedAvatarFallback
                                ELSE CASE
                                    WHEN low.display_name IS NULL OR BTRIM(low.display_name) = '' THEN '?'
                                    ELSE SUBSTRING(low.display_name FROM 1 FOR 1)
                                END
                            END,
                            readonly_high_display_name = CASE
                                WHEN conversation.user_high_id = :userId THEN :deletedDisplayName
                                ELSE high.display_name
                            END,
                            readonly_high_avatar_fallback = CASE
                                WHEN conversation.user_high_id = :userId THEN :deletedAvatarFallback
                                ELSE CASE
                                    WHEN high.display_name IS NULL OR BTRIM(high.display_name) = '' THEN '?'
                                    ELSE SUBSTRING(high.display_name FROM 1 FOR 1)
                                END
                            END
                        FROM users low, users high
                        WHERE conversation.user_low_id = low.id
                          AND conversation.user_high_id = high.id
                          AND (conversation.user_low_id = :userId
                               OR conversation.user_high_id = :userId)
                        """)
                .param("userId", userId)
                .param("deletedDisplayName", DELETED_DISPLAY_NAME)
                .param("deletedAvatarFallback", DELETED_AVATAR_FALLBACK)
                .update();
        jdbc.sql("""
                        UPDATE conversations conversation
                        SET status = 'READ_ONLY'
                        WHERE conversation.type = 'C2C'
                          AND conversation.status = 'ACTIVE'
                          AND EXISTS (
                              SELECT 1
                              FROM c2c_conversations c2c
                              WHERE c2c.conversation_id = conversation.id
                                AND (c2c.user_low_id = :userId OR c2c.user_high_id = :userId)
                          )
                        """)
                .param("userId", userId)
                .update();
    }

    private void deletePrivateRelationships(UUID userId) {
        jdbc.sql("""
                        DELETE FROM group_join_requests
                        WHERE user_id = :userId
                           OR reviewed_by_user_id = :userId
                           OR invite_id IN (
                               SELECT id FROM group_invites WHERE created_by_user_id = :userId
                           )
                        """)
                .param("userId", userId)
                .update();
        jdbc.sql("DELETE FROM group_invites WHERE created_by_user_id = :userId")
                .param("userId", userId)
                .update();
        jdbc.sql("""
                        DELETE FROM group_bans
                        WHERE user_id = :userId OR actor_user_id = :userId
                        """)
                .param("userId", userId)
                .update();
        jdbc.sql("DELETE FROM conversation_members WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        jdbc.sql("DELETE FROM conversation_read_states WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        jdbc.sql("""
                        DELETE FROM contacts
                        WHERE user_low_id = :userId OR user_high_id = :userId
                        """)
                .param("userId", userId)
                .update();
        jdbc.sql("""
                        DELETE FROM user_blocks
                        WHERE blocker_id = :userId OR blocked_id = :userId
                        """)
                .param("userId", userId)
                .update();
        jdbc.sql("""
                        DELETE FROM contact_requests
                        WHERE requester_id = :userId OR recipient_id = :userId
                        """)
                .param("userId", userId)
                .update();
        jdbc.sql("""
                        DELETE FROM outbox
                        WHERE target_device_id IN (
                            SELECT id FROM devices WHERE user_id = :userId
                        )
                        """)
                .param("userId", userId)
                .update();
        jdbc.sql("DELETE FROM device_sync_states WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        jdbc.sql("DELETE FROM user_sync_events WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        jdbc.sql("DELETE FROM user_sync_counters WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        jdbc.sql("DELETE FROM login_challenges WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    private void expireAvatarRows(UUID userId) {
        jdbc.sql("""
                        UPDATE media
                        SET state = 'EXPIRED',
                            expired_at = COALESCE(expired_at, CURRENT_TIMESTAMP),
                            attached_message_id = NULL,
                            attached_entity_id = NULL,
                            attached_entity_type = NULL,
                            bound_at = NULL
                        WHERE uploader_id = :userId
                          AND purpose = 'AVATAR'
                          AND state <> 'EXPIRED'
                        """)
                .param("userId", userId)
                .update();
    }

    private void anonymizeUser(UUID userId) {
        jdbc.sql("""
                        UPDATE users
                        SET display_name = :displayName,
                            password_hash = :passwordHash,
                            password_must_change = FALSE,
                            temporary_password_used = FALSE,
                            searchable_by_account_no = FALSE,
                            avatar_media_id = NULL,
                            avatar_version = avatar_version + 1
                        WHERE id = :userId AND status = 'DELETED'
                        """)
                .param("userId", userId)
                .param("displayName", DELETED_DISPLAY_NAME)
                .param("passwordHash", TokenDigests.sha256(UuidV7.random().toString()))
                .update();
    }

    private void registerAvatarObjectCleanup(List<MediaObject> objects) {
        if (objects.isEmpty()) {
            return;
        }
        Runnable cleanup = () -> objects.forEach(this::deleteAvatarObjects);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            cleanup.run();
                        }
                    });
        } else {
            cleanup.run();
        }
    }

    private void deleteAvatarObjects(MediaObject object) {
        try {
            mediaStorage.delete(object.originalObjectKey());
            mediaStorage.delete(object.thumbnailObjectKey());
            jdbc.sql("""
                            UPDATE media
                            SET objects_deleted_at = CURRENT_TIMESTAMP
                            WHERE id = :mediaId AND state = 'EXPIRED'
                            """)
                    .param("mediaId", object.mediaId())
                    .update();
        } catch (RuntimeException ignored) {
            // The regular media cleanup job retries object deletion.
        }
    }

    private record MediaObject(
            UUID mediaId,
            String originalObjectKey,
            String thumbnailObjectKey
    ) {
    }
}
