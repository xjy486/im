package com.jitong.im.group;

import com.jitong.im.auth.PublicNumberGenerator;
import com.jitong.im.auth.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
class GroupRepository {

    private final JdbcClient jdbc;

    GroupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    GroupRecord createGroup(
            UUID ownerUserId,
            String name,
            String description,
            GroupVisibility visibility,
            PublicNumberGenerator numberGenerator
    ) {
        UUID conversationId = UuidV7.random();
        for (int attempt = 0; attempt < 10; attempt++) {
            String groupNo = numberGenerator.next();
            int claimed = jdbc.sql("""
                            INSERT INTO public_identifiers (public_no, entity_type, entity_id)
                            VALUES (:groupNo, 'GROUP', :conversationId)
                            ON CONFLICT (public_no) DO NOTHING
                            """)
                    .param("groupNo", groupNo)
                    .param("conversationId", conversationId)
                    .update();
            if (claimed == 0) {
                continue;
            }

            jdbc.sql("""
                            INSERT INTO conversations (id, type, status)
                            VALUES (:conversationId, 'GROUP', 'ACTIVE')
                            """)
                    .param("conversationId", conversationId)
                    .update();
            jdbc.sql("""
                            INSERT INTO groups (
                                conversation_id, group_no, name, description,
                                name_normalized, description_normalized,
                                visibility, owner_user_id, status)
                            VALUES (
                                :conversationId, :groupNo, :name, :description,
                                :nameNormalized, :descriptionNormalized,
                                :visibility, :ownerUserId, 'ACTIVE')
                            """)
                    .param("conversationId", conversationId)
                    .param("groupNo", groupNo)
                    .param("name", name)
                    .param("description", description)
                    .param("nameNormalized", GroupText.normalize(name))
                    .param("descriptionNormalized", GroupText.normalize(description))
                    .param("visibility", visibility.name())
                    .param("ownerUserId", ownerUserId)
                    .update();
            jdbc.sql("""
                            INSERT INTO conversation_members (
                                conversation_id, user_id, role, status,
                                history_visible_after_seq, read_seq, membership_version)
                            VALUES (:conversationId, :ownerUserId, 'OWNER', 'ACTIVE', 0, 0, 1)
                            """)
                    .param("conversationId", conversationId)
                    .param("ownerUserId", ownerUserId)
                    .update();
            return findGroupForUser(conversationId, ownerUserId);
        }
        throw new IllegalStateException("Could not allocate a public group number");
    }

    void lockOwner(UUID ownerUserId) {
        jdbc.sql("""
                        SELECT id
                        FROM users
                        WHERE id = :ownerUserId AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("ownerUserId", ownerUserId)
                .query(UUID.class)
                .single();
    }

    GroupRecord findGroupForUser(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT g.conversation_id, g.group_no, g.name, g.description,
                               g.visibility, g.owner_user_id, g.avatar_media_id,
                               g.avatar_version, member.role,
                               COALESCE(ai.enabled, FALSE) AS ai_enabled,
                               COALESCE(ai.policy_version, 1) AS ai_policy_version,
                               (
                                   SELECT COUNT(*)
                                   FROM conversation_members active_member
                                   WHERE active_member.conversation_id = g.conversation_id
                                     AND active_member.status = 'ACTIVE'
                               ) AS member_count
                        FROM groups g
                        JOIN conversation_members member
                          ON member.conversation_id = g.conversation_id
                         AND member.user_id = :userId
                         AND member.status = 'ACTIVE'
                        LEFT JOIN conversation_ai_settings ai
                          ON ai.conversation_id = g.conversation_id
                        WHERE g.conversation_id = :conversationId
                          AND g.status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(this::mapGroup)
                .optional()
                .orElse(null);
    }

    List<GroupRecord> listGroupsForUser(UUID userId) {
        return jdbc.sql("""
                        SELECT g.conversation_id, g.group_no, g.name, g.description,
                               g.visibility, g.owner_user_id, g.avatar_media_id,
                               g.avatar_version, member.role,
                               COALESCE(ai.enabled, FALSE) AS ai_enabled,
                               COALESCE(ai.policy_version, 1) AS ai_policy_version,
                               COUNT(active_member.user_id) AS member_count
                        FROM groups g
                        JOIN conversation_members member
                          ON member.conversation_id = g.conversation_id
                         AND member.user_id = :userId
                         AND member.status = 'ACTIVE'
                        LEFT JOIN conversation_members active_member
                          ON active_member.conversation_id = g.conversation_id
                         AND active_member.status = 'ACTIVE'
                        LEFT JOIN conversation_ai_settings ai
                          ON ai.conversation_id = g.conversation_id
                        WHERE g.status = 'ACTIVE'
                        GROUP BY g.conversation_id, g.group_no, g.name, g.description,
                                 g.visibility, g.owner_user_id, g.avatar_media_id,
                                 g.avatar_version, member.role, ai.enabled, ai.policy_version
                        ORDER BY g.created_at DESC
                        """)
                .param("userId", userId)
                .query(this::mapGroup)
                .list();
    }

    List<UUID> activeMemberIds(UUID conversationId) {
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

    GroupActor lockActor(UUID conversationId, UUID actorId) {
        return jdbc.sql("""
                        SELECT g.conversation_id, member.role,
                               (
                                   SELECT COUNT(*)
                                   FROM conversation_members active_member
                                   WHERE active_member.conversation_id = g.conversation_id
                                     AND active_member.status = 'ACTIVE'
                               ) AS member_count
                        FROM groups g
                        JOIN conversation_members member
                          ON member.conversation_id = g.conversation_id
                         AND member.user_id = :actorId
                         AND member.status = 'ACTIVE'
                        WHERE g.conversation_id = :conversationId
                          AND g.status = 'ACTIVE'
                          AND member.role IN ('OWNER', 'ADMIN')
                        FOR UPDATE OF g
                        """)
                .param("conversationId", conversationId)
                .param("actorId", actorId)
                .query((row, rowNum) -> new GroupActor(
                        row.getObject("conversation_id", UUID.class),
                        row.getString("role"),
                        row.getInt("member_count")))
                .optional()
                .orElse(null);
    }

    UUID findActiveUserByAccountNoForUpdate(String accountNo) {
        return jdbc.sql("""
                        SELECT id
                        FROM users
                        WHERE account_no = :accountNo
                          AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("accountNo", accountNo)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    boolean isActiveMember(UUID conversationId, UUID userId) {
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

    String memberRole(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT role
                        FROM conversation_members
                        WHERE conversation_id = :conversationId
                          AND user_id = :userId
                          AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    int updateMemberRole(UUID conversationId, UUID userId, String role) {
        return jdbc.sql("""
                        UPDATE conversation_members
                        SET role = :role,
                            membership_version = membership_version + 1
                        WHERE conversation_id = :conversationId
                          AND user_id = :userId
                          AND status = 'ACTIVE'
                          AND role <> 'OWNER'
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .param("role", role)
                .update();
    }

    int transferOwnership(UUID conversationId, UUID previousOwnerUserId, UUID newOwnerUserId) {
        int demoted = jdbc.sql("""
                        UPDATE conversation_members
                        SET role = 'ADMIN',
                            membership_version = membership_version + 1
                        WHERE conversation_id = :conversationId
                          AND user_id = :previousOwnerUserId
                          AND role = 'OWNER'
                          AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("previousOwnerUserId", previousOwnerUserId)
                .update();
        if (demoted != 1) {
            return 0;
        }
        int promoted = jdbc.sql("""
                        UPDATE conversation_members
                        SET role = 'OWNER',
                            membership_version = membership_version + 1
                        WHERE conversation_id = :conversationId
                          AND user_id = :newOwnerUserId
                          AND status = 'ACTIVE'
                          AND role <> 'OWNER'
                        """)
                .param("conversationId", conversationId)
                .param("newOwnerUserId", newOwnerUserId)
                .update();
        if (promoted != 1) {
            throw new IllegalStateException("New owner is not an active non-owner member");
        }
        jdbc.sql("""
                        UPDATE groups
                        SET owner_user_id = :newOwnerUserId
                        WHERE conversation_id = :conversationId
                          AND owner_user_id = :previousOwnerUserId
                          AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("previousOwnerUserId", previousOwnerUserId)
                .param("newOwnerUserId", newOwnerUserId)
                .update();
        return 1;
    }

    void updateGroupProfile(
            UUID conversationId,
            String name,
            String description,
            GroupVisibility visibility
    ) {
        jdbc.sql("""
                        UPDATE groups
                        SET name = :name,
                            description = :description,
                            name_normalized = :nameNormalized,
                            description_normalized = :descriptionNormalized,
                            visibility = :visibility
                        WHERE conversation_id = :conversationId
                          AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("name", name)
                .param("description", description)
                .param("nameNormalized", GroupText.normalize(name))
                .param("descriptionNormalized", GroupText.normalize(description))
                .param("visibility", visibility.name())
                .update();
    }

    void removeMember(UUID conversationId, UUID userId, Instant leftAt) {
        jdbc.sql("""
                        UPDATE conversation_members
                        SET status = 'REMOVED',
                            left_at = :leftAt
                        WHERE conversation_id = :conversationId
                          AND user_id = :userId
                          AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .param("leftAt", utc(leftAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    DissolutionRecord dissolve(
            UUID conversationId,
            UUID ownerUserId,
            Instant dissolvedAt,
            Instant purgeAfter
    ) {
        DissolutionRecord group = jdbc.sql("""
                        SELECT conversation_id, group_no, owner_user_id
                        FROM groups
                        WHERE conversation_id = :conversationId
                          AND owner_user_id = :ownerUserId
                          AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("conversationId", conversationId)
                .param("ownerUserId", ownerUserId)
                .query((row, rowNum) -> new DissolutionRecord(
                        row.getObject("conversation_id", UUID.class),
                        row.getString("group_no").trim(),
                        row.getObject("owner_user_id", UUID.class)))
                .optional()
                .orElse(null);
        if (group == null) {
            return null;
        }

        jdbc.sql("""
                        UPDATE groups
                        SET status = 'DISSOLVED',
                            dissolved_at = :dissolvedAt,
                            purge_after = :purgeAfter
                        WHERE conversation_id = :conversationId
                          AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("dissolvedAt", utc(dissolvedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("purgeAfter", utc(purgeAfter), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE conversations
                        SET status = 'DISSOLVED'
                        WHERE id = :conversationId
                          AND type = 'GROUP'
                          AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .update();
        jdbc.sql("""
                        UPDATE conversation_members
                        SET status = 'REMOVED',
                            left_at = :dissolvedAt
                        WHERE conversation_id = :conversationId
                          AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("dissolvedAt", utc(dissolvedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE public_identifiers
                        SET retired_at = :dissolvedAt
                        WHERE entity_type = 'GROUP'
                          AND entity_id = :conversationId
                          AND retired_at IS NULL
                        """)
                .param("conversationId", conversationId)
                .param("dissolvedAt", utc(dissolvedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        INSERT INTO group_dissolution_audit (
                            conversation_id, group_no, owner_user_id, dissolved_at
                        ) VALUES (
                            :conversationId, :groupNo, :ownerUserId, :dissolvedAt
                        )
                        ON CONFLICT (conversation_id) DO NOTHING
                        """)
                .param("conversationId", conversationId)
                .param("groupNo", group.groupNo())
                .param("ownerUserId", group.ownerUserId())
                .param("dissolvedAt", utc(dissolvedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return group;
    }

    List<DissolvedGroupRecord> findGroupsDueForPurge(Instant now) {
        return jdbc.sql("""
                        SELECT conversation_id, group_no, owner_user_id,
                               dissolved_at, purge_after
                        FROM groups
                        WHERE status = 'DISSOLVED'
                          AND purge_after <= :now
                        ORDER BY purge_after, conversation_id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 25
                        """)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((row, rowNum) -> new DissolvedGroupRecord(
                        row.getObject("conversation_id", UUID.class),
                        row.getString("group_no").trim(),
                        row.getObject("owner_user_id", UUID.class),
                        row.getObject("dissolved_at", OffsetDateTime.class).toInstant(),
                        row.getObject("purge_after", OffsetDateTime.class).toInstant()))
                .list();
    }

    List<UUID> purgeGroupContent(UUID conversationId) {
        List<UUID> mediaIds = jdbc.sql("""
                        SELECT media_id
                        FROM messages
                        WHERE conversation_id = :conversationId
                          AND media_id IS NOT NULL
                        UNION
                        SELECT avatar_media_id
                        FROM groups
                        WHERE conversation_id = :conversationId
                          AND avatar_media_id IS NOT NULL
                        """)
                .param("conversationId", conversationId)
                .query(UUID.class)
                .list();
        jdbc.sql("""
                        DELETE FROM outbox
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .update();
        // Keep the content-free GROUP_DISSOLVED sync marker. Removing an event
        // from the middle of a user's ordered sync stream would create a gap
        // and incorrectly force unrelated devices into a full reset.
        jdbc.sql("""
                        DELETE FROM group_join_requests
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .update();
        jdbc.sql("""
                        DELETE FROM group_invites
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .update();
        jdbc.sql("""
                        DELETE FROM group_bans
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .update();
        jdbc.sql("""
                        DELETE FROM messages
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .update();
        for (UUID mediaId : mediaIds) {
            jdbc.sql("""
                            UPDATE media
                            SET state = 'EXPIRED',
                                expired_at = COALESCE(expired_at, CURRENT_TIMESTAMP),
                                attached_message_id = NULL,
                                attached_entity_id = NULL,
                                attached_entity_type = NULL,
                                bound_at = NULL
                            WHERE id = :mediaId
                            """)
                    .param("mediaId", mediaId)
                    .update();
        }
        jdbc.sql("""
                        UPDATE groups
                        SET avatar_media_id = NULL
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .update();
        jdbc.sql("""
                        UPDATE group_dissolution_audit
                        SET purged_at = CURRENT_TIMESTAMP
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .update();
        return mediaIds;
    }

    String groupNo(UUID conversationId) {
        return jdbc.sql("""
                        SELECT group_no
                        FROM groups
                        WHERE conversation_id = :conversationId
                          AND status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .query(String.class)
                .optional()
                .map(String::trim)
                .orElse(null);
    }

    GroupRecord findGroup(UUID conversationId) {
        return jdbc.sql("""
                        SELECT g.conversation_id, g.group_no, g.name, g.description,
                               g.visibility, g.owner_user_id, g.avatar_media_id,
                               g.avatar_version,
                               COALESCE(ai.enabled, FALSE) AS ai_enabled,
                               COALESCE(ai.policy_version, 1) AS ai_policy_version,
                               (
                                   SELECT COUNT(*)
                                   FROM conversation_members active_member
                                   WHERE active_member.conversation_id = g.conversation_id
                                     AND active_member.status = 'ACTIVE'
                               ) AS member_count
                        FROM groups g
                        LEFT JOIN conversation_ai_settings ai
                          ON ai.conversation_id = g.conversation_id
                        WHERE g.conversation_id = :conversationId
                          AND g.status = 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .query((row, rowNum) -> new GroupRecord(
                        row.getObject("conversation_id", UUID.class),
                        row.getString("group_no").trim(),
                        row.getString("name"),
                        row.getString("description"),
                        row.getString("visibility"),
                        row.getObject("owner_user_id", UUID.class),
                        row.getObject("avatar_media_id", UUID.class),
                        row.getLong("avatar_version"),
                        null,
                        row.getInt("member_count"),
                        row.getBoolean("ai_enabled"),
                        row.getLong("ai_policy_version")))
                .optional()
                .orElse(null);
    }

    GroupRecord lockGroup(UUID conversationId) {
        return jdbc.sql("""
                        SELECT g.conversation_id, g.group_no, g.name, g.description,
                               g.visibility, g.owner_user_id, g.avatar_media_id,
                               g.avatar_version,
                               COALESCE(ai.enabled, FALSE) AS ai_enabled,
                               COALESCE(ai.policy_version, 1) AS ai_policy_version,
                               (
                                   SELECT COUNT(*)
                                   FROM conversation_members active_member
                                   WHERE active_member.conversation_id = g.conversation_id
                                     AND active_member.status = 'ACTIVE'
                               ) AS member_count
                        FROM groups g
                        LEFT JOIN conversation_ai_settings ai
                          ON ai.conversation_id = g.conversation_id
                        WHERE g.conversation_id = :conversationId
                          AND g.status = 'ACTIVE'
                        FOR UPDATE OF g
                        """)
                .param("conversationId", conversationId)
                .query((row, rowNum) -> new GroupRecord(
                        row.getObject("conversation_id", UUID.class),
                        row.getString("group_no").trim(),
                        row.getString("name"),
                        row.getString("description"),
                        row.getString("visibility"),
                        row.getObject("owner_user_id", UUID.class),
                        row.getObject("avatar_media_id", UUID.class),
                        row.getLong("avatar_version"),
                        null,
                        row.getInt("member_count"),
                        row.getBoolean("ai_enabled"),
                        row.getLong("ai_policy_version")))
                .optional()
                .orElse(null);
    }

    boolean activeUserExists(UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM users
                            WHERE id = :userId AND status = 'ACTIVE'
                        )
                        """)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    int addMember(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        WITH group_state AS (
                            SELECT conversation.last_seq
                            FROM conversations conversation
                            WHERE conversation.id = :conversationId
                            FOR UPDATE
                        )
                        INSERT INTO conversation_members (
                            conversation_id, user_id, role, status,
                            history_visible_after_seq, read_seq, membership_version)
                        SELECT :conversationId, :userId, 'MEMBER', 'ACTIVE',
                               group_state.last_seq, 0, 1
                        FROM group_state
                        ON CONFLICT (conversation_id, user_id)
                        DO UPDATE SET role = 'MEMBER',
                                      status = 'ACTIVE',
                                      left_at = NULL,
                                      joined_at = CURRENT_TIMESTAMP,
                                      history_visible_after_seq = (
                                          SELECT last_seq
                                          FROM conversations
                                          WHERE id = :conversationId
                                      ),
                                      read_seq = 0,
                                      membership_version = conversation_members.membership_version + 1
                        WHERE conversation_members.status <> 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
    }

    GroupInviteRecord insertInvite(
            UUID inviteId,
            UUID conversationId,
            UUID createdByUserId,
            String tokenHash,
            Instant expiresAt,
            int maxUses
    ) {
        jdbc.sql("""
                        INSERT INTO group_invites (
                            id, conversation_id, created_by_user_id, token_hash,
                            expires_at, max_uses
                        ) VALUES (
                            :id, :conversationId, :createdByUserId, :tokenHash,
                            :expiresAt, :maxUses
                        )
                        """)
                .param("id", inviteId)
                .param("conversationId", conversationId)
                .param("createdByUserId", createdByUserId)
                .param("tokenHash", tokenHash)
                .param("expiresAt", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("maxUses", maxUses)
                .update();
        return findInvite(inviteId);
    }

    GroupInviteRecord findInvite(UUID inviteId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, created_by_user_id, token_hash,
                               expires_at, max_uses, use_count, status, created_at
                        FROM group_invites
                        WHERE id = :inviteId
                        """)
                .param("inviteId", inviteId)
                .query(this::mapInvite)
                .optional()
                .orElse(null);
    }

    GroupInviteRecord lockInviteByTokenHash(String tokenHash) {
        return jdbc.sql("""
                        SELECT id, conversation_id, created_by_user_id, token_hash,
                               expires_at, max_uses, use_count, status, created_at
                        FROM group_invites
                        WHERE token_hash = :tokenHash
                        FOR UPDATE
                        """)
                .param("tokenHash", tokenHash)
                .query(this::mapInvite)
                .optional()
                .orElse(null);
    }

    GroupInviteRecord findInviteByTokenHash(String tokenHash) {
        return jdbc.sql("""
                        SELECT id, conversation_id, created_by_user_id, token_hash,
                               expires_at, max_uses, use_count, status, created_at
                        FROM group_invites
                        WHERE token_hash = :tokenHash
                        """)
                .param("tokenHash", tokenHash)
                .query(this::mapInvite)
                .optional()
                .orElse(null);
    }

    GroupInviteRecord lockInvite(UUID inviteId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, created_by_user_id, token_hash,
                               expires_at, max_uses, use_count, status, created_at
                        FROM group_invites
                        WHERE id = :inviteId
                        FOR UPDATE
                        """)
                .param("inviteId", inviteId)
                .query(this::mapInvite)
                .optional()
                .orElse(null);
    }

    void incrementInviteUse(UUID inviteId) {
        jdbc.sql("""
                        UPDATE group_invites
                        SET use_count = use_count + 1
                        WHERE id = :inviteId
                        """)
                .param("inviteId", inviteId)
                .update();
    }

    void revokeInvite(UUID conversationId, UUID inviteId, Instant revokedAt) {
        jdbc.sql("""
                        UPDATE group_invites
                        SET status = 'REVOKED',
                            revoked_at = :revokedAt
                        WHERE id = :inviteId
                          AND conversation_id = :conversationId
                          AND status = 'ACTIVE'
                        """)
                .param("inviteId", inviteId)
                .param("conversationId", conversationId)
                .param("revokedAt", utc(revokedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    JoinRequestRecord insertJoinRequest(
            UUID requestId,
            UUID conversationId,
            UUID userId,
            UUID inviteId,
            Instant createdAt
    ) {
        int inserted = jdbc.sql("""
                        INSERT INTO group_join_requests (
                            id, conversation_id, user_id, invite_id, created_at
                        ) VALUES (
                            :id, :conversationId, :userId, :inviteId, :createdAt
                        )
                        ON CONFLICT (conversation_id, user_id)
                        WHERE status = 'PENDING'
                        DO NOTHING
                        """)
                .param("id", requestId)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .param("inviteId", inviteId)
                .param("createdAt", utc(createdAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return inserted == 0 ? null : findJoinRequest(requestId);
    }

    JoinRequestRecord findPendingJoinRequest(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, user_id, invite_id, status,
                               created_at, resolved_at
                        FROM group_join_requests
                        WHERE conversation_id = :conversationId
                          AND user_id = :userId
                          AND status = 'PENDING'
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(this::mapJoinRequest)
                .optional()
                .orElse(null);
    }

    JoinRequestRecord findJoinRequest(UUID requestId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, user_id, invite_id, status,
                               created_at, resolved_at
                        FROM group_join_requests
                        WHERE id = :requestId
                        """)
                .param("requestId", requestId)
                .query(this::mapJoinRequest)
                .optional()
                .orElse(null);
    }

    JoinRequestRecord lockJoinRequest(UUID requestId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, user_id, invite_id, status,
                               created_at, resolved_at
                        FROM group_join_requests
                        WHERE id = :requestId
                        FOR UPDATE
                        """)
                .param("requestId", requestId)
                .query(this::mapJoinRequest)
                .optional()
                .orElse(null);
    }

    void updateJoinRequestStatus(
            UUID requestId,
            String status,
            UUID reviewerId,
            Instant resolvedAt
    ) {
        jdbc.sql("""
                        UPDATE group_join_requests
                        SET status = :status,
                            reviewed_by_user_id = :reviewerId,
                            resolved_at = :resolvedAt
                        WHERE id = :requestId
                        """)
                .param("requestId", requestId)
                .param("status", status)
                .param("reviewerId", reviewerId)
                .param("resolvedAt", utc(resolvedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    List<JoinRequestRecord> listJoinRequests(UUID conversationId) {
        return jdbc.sql("""
                        SELECT request.id, request.conversation_id, request.user_id,
                               request.invite_id, request.status, request.created_at,
                               request.resolved_at, applicant.account_no, applicant.display_name
                        FROM group_join_requests request
                        JOIN users applicant ON applicant.id = request.user_id
                        WHERE request.conversation_id = :conversationId
                        ORDER BY request.created_at DESC
                        """)
                .param("conversationId", conversationId)
                .query((row, rowNum) -> new JoinRequestRecord(
                        row.getObject("id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("user_id", UUID.class),
                        row.getObject("invite_id", UUID.class),
                        row.getString("status"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        nullableInstant(row, "resolved_at"),
                        row.getString("account_no").trim(),
                        row.getString("display_name")))
                .list();
    }

    List<MyJoinRequestRecord> listJoinRequestsForUser(UUID userId) {
        return jdbc.sql("""
                        SELECT request.id, request.conversation_id, group_chat.group_no,
                               group_chat.name, request.status, request.created_at,
                               request.resolved_at
                        FROM group_join_requests request
                        JOIN groups group_chat
                          ON group_chat.conversation_id = request.conversation_id
                        WHERE request.user_id = :userId
                        ORDER BY request.created_at DESC
                        """)
                .param("userId", userId)
                .query((row, rowNum) -> new MyJoinRequestRecord(
                        row.getObject("id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getString("group_no").trim(),
                        row.getString("name"),
                        row.getString("status"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        nullableInstant(row, "resolved_at")))
                .list();
    }

    List<MemberRecord> listMembers(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT member.user_id, user_account.account_no,
                               user_account.display_name, member.role,
                               user_account.avatar_media_id, user_account.avatar_version
                        FROM conversation_members member
                        JOIN users user_account ON user_account.id = member.user_id
                        JOIN groups group_chat
                          ON group_chat.conversation_id = member.conversation_id
                         AND group_chat.status = 'ACTIVE'
                        WHERE member.conversation_id = :conversationId
                          AND member.status = 'ACTIVE'
                          AND EXISTS (
                              SELECT 1
                              FROM conversation_members actor
                              WHERE actor.conversation_id = member.conversation_id
                                AND actor.user_id = :userId
                                AND actor.status = 'ACTIVE'
                          )
                        ORDER BY CASE member.role
                            WHEN 'OWNER' THEN 0
                            WHEN 'ADMIN' THEN 1
                            ELSE 2
                        END,
                        user_account.display_name,
                        member.user_id
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query((row, rowNum) -> new MemberRecord(
                        row.getObject("user_id", UUID.class),
                        row.getString("account_no").trim(),
                        row.getString("display_name"),
                        row.getString("role"),
                        row.getObject("avatar_media_id", UUID.class),
                        row.getLong("avatar_version")))
                .list();
    }

    GroupRecord findActiveGroupByGroupNo(String groupNo) {
        return jdbc.sql("""
                        SELECT g.conversation_id, g.group_no, g.name, g.description,
                               g.visibility, g.owner_user_id, g.avatar_media_id,
                               g.avatar_version,
                               COALESCE(ai.enabled, FALSE) AS ai_enabled,
                               COALESCE(ai.policy_version, 1) AS ai_policy_version,
                               (
                                   SELECT COUNT(*)
                                   FROM conversation_members active_member
                                   WHERE active_member.conversation_id = g.conversation_id
                                     AND active_member.status = 'ACTIVE'
                               ) AS member_count
                        FROM groups g
                        LEFT JOIN conversation_ai_settings ai
                          ON ai.conversation_id = g.conversation_id
                        WHERE g.group_no = :groupNo
                          AND g.status = 'ACTIVE'
                        FOR UPDATE OF g
                        """)
                .param("groupNo", groupNo)
                .query((row, rowNum) -> new GroupRecord(
                        row.getObject("conversation_id", UUID.class),
                        row.getString("group_no").trim(),
                        row.getString("name"),
                        row.getString("description"),
                        row.getString("visibility"),
                        row.getObject("owner_user_id", UUID.class),
                        row.getObject("avatar_media_id", UUID.class),
                        row.getLong("avatar_version"),
                        null,
                        row.getInt("member_count"),
                        row.getBoolean("ai_enabled"),
                        row.getLong("ai_policy_version")))
                .optional()
                .orElse(null);
    }

    boolean isBanned(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM group_bans
                            WHERE conversation_id = :conversationId
                              AND user_id = :userId
                        )
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    void ban(UUID conversationId, UUID userId, UUID actorId, String reason, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO group_bans (
                            conversation_id, user_id, actor_user_id, reason, created_at
                        ) VALUES (
                            :conversationId, :userId, :actorId, :reason, :createdAt
                        )
                        ON CONFLICT (conversation_id, user_id)
                        DO UPDATE SET actor_user_id = EXCLUDED.actor_user_id,
                                      reason = EXCLUDED.reason,
                                      created_at = EXCLUDED.created_at
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .param("actorId", actorId)
                .param("reason", reason)
                .param("createdAt", utc(createdAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    void removeBan(UUID conversationId, UUID userId) {
        jdbc.sql("""
                        DELETE FROM group_bans
                        WHERE conversation_id = :conversationId AND user_id = :userId
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
    }

    private GroupInviteRecord mapInvite(java.sql.ResultSet row, int rowNum)
            throws java.sql.SQLException {
        return new GroupInviteRecord(
                row.getObject("id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getObject("created_by_user_id", UUID.class),
                row.getString("token_hash"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                row.getInt("max_uses"),
                row.getInt("use_count"),
                row.getString("status"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private JoinRequestRecord mapJoinRequest(java.sql.ResultSet row, int rowNum)
            throws java.sql.SQLException {
        return new JoinRequestRecord(
                row.getObject("id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getObject("user_id", UUID.class),
                row.getObject("invite_id", UUID.class),
                row.getString("status"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                nullableInstant(row, "resolved_at"));
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC);
    }

    private static Instant nullableInstant(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    SearchGroupRecord findSearchableByGroupNo(String groupNo) {
        return jdbc.sql("""
                        SELECT g.conversation_id, g.name, g.description,
                               g.avatar_media_id, g.avatar_version,
                               COUNT(member.user_id) AS member_count
                        FROM groups g
                        LEFT JOIN conversation_members member
                          ON member.conversation_id = g.conversation_id
                         AND member.status = 'ACTIVE'
                        WHERE g.group_no = :groupNo
                          AND g.status = 'ACTIVE'
                          AND g.visibility IN ('PUBLIC', 'UNLISTED')
                        GROUP BY g.conversation_id, g.name, g.description,
                                 g.avatar_media_id, g.avatar_version
                        """)
                .param("groupNo", groupNo)
                .query(this::mapSearchGroup)
                .optional()
                .orElse(null);
    }

    List<SearchGroupRecord> searchPublicGroups(String normalizedQuery) {
        return jdbc.sql("""
                        SELECT g.conversation_id, g.name, g.description,
                               g.avatar_media_id, g.avatar_version,
                               COUNT(member.user_id) AS member_count,
                               similarity(g.name_normalized, :query) AS search_similarity
                        FROM groups g
                        LEFT JOIN conversation_members member
                          ON member.conversation_id = g.conversation_id
                         AND member.status = 'ACTIVE'
                        WHERE g.status = 'ACTIVE'
                          AND g.visibility = 'PUBLIC'
                          AND (
                              g.name_normalized ILIKE '%' || :query || '%'
                              OR g.name_normalized % :query
                          )
                        GROUP BY g.conversation_id, g.name, g.description,
                                 g.avatar_media_id, g.avatar_version, g.name_normalized
                        ORDER BY search_similarity DESC, g.name_normalized ASC,
                                 g.conversation_id ASC
                        LIMIT 50
                        """)
                .param("query", normalizedQuery)
                .query((row, rowNum) -> mapSearchGroup(row, rowNum))
                .list();
    }

    private GroupRecord mapGroup(java.sql.ResultSet row, int rowNum) throws java.sql.SQLException {
        return new GroupRecord(
                row.getObject("conversation_id", UUID.class),
                row.getString("group_no").trim(),
                row.getString("name"),
                row.getString("description"),
                row.getString("visibility"),
                row.getObject("owner_user_id", UUID.class),
                row.getObject("avatar_media_id", UUID.class),
                row.getLong("avatar_version"),
                row.getString("role"),
                row.getInt("member_count"),
                row.getBoolean("ai_enabled"),
                row.getLong("ai_policy_version"));
    }

    private SearchGroupRecord mapSearchGroup(
            java.sql.ResultSet row,
            int rowNum
    ) throws java.sql.SQLException {
        return new SearchGroupRecord(
                row.getObject("conversation_id", UUID.class),
                row.getString("name"),
                row.getString("description"),
                row.getObject("avatar_media_id", UUID.class),
                row.getLong("avatar_version"),
                row.getInt("member_count"));
    }

    record GroupRecord(
            UUID conversationId,
            String groupNo,
            String name,
            String description,
            String visibility,
            UUID ownerUserId,
            UUID avatarMediaId,
            long avatarVersion,
            String role,
            int memberCount,
            boolean aiEnabled,
            long aiPolicyVersion
    ) {
    }

    record SearchGroupRecord(
            UUID conversationId,
            String name,
            String description,
            UUID avatarMediaId,
            long avatarVersion,
            int memberCount
    ) {
    }

    record GroupActor(UUID conversationId, String role, int memberCount) {
    }

    record DissolutionRecord(UUID conversationId, String groupNo, UUID ownerUserId) {
    }

    record DissolvedGroupRecord(
            UUID conversationId,
            String groupNo,
            UUID ownerUserId,
            Instant dissolvedAt,
            Instant purgeAfter
    ) {
    }

    record GroupInviteRecord(
            UUID inviteId,
            UUID conversationId,
            UUID createdByUserId,
            String tokenHash,
            Instant expiresAt,
            int maxUses,
            int useCount,
            String status,
            Instant createdAt
    ) {
    }

    record JoinRequestRecord(
            UUID requestId,
            UUID conversationId,
            UUID userId,
            UUID inviteId,
            String status,
            Instant createdAt,
            Instant resolvedAt,
            String accountNo,
            String displayName
    ) {
        JoinRequestRecord(
                UUID requestId,
                UUID conversationId,
                UUID userId,
                UUID inviteId,
                String status,
                Instant createdAt,
                Instant resolvedAt
        ) {
            this(
                    requestId,
                    conversationId,
                    userId,
                    inviteId,
                    status,
                    createdAt,
                    resolvedAt,
                    null,
                    null);
        }
    }

    record MyJoinRequestRecord(
            UUID requestId,
            UUID conversationId,
            String groupNo,
            String groupName,
            String status,
            Instant createdAt,
            Instant resolvedAt
    ) {
    }

    record MemberRecord(
            UUID userId,
            String accountNo,
            String displayName,
            String role,
            UUID avatarMediaId,
            long avatarVersion
    ) {
    }
}
