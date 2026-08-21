package com.jitong.im.group;

import com.jitong.im.auth.PublicNumberGenerator;
import com.jitong.im.auth.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
                                history_visible_after_seq, membership_version)
                            VALUES (:conversationId, :ownerUserId, 'OWNER', 'ACTIVE', 0, 1)
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
                               COUNT(active_member.user_id) AS member_count
                        FROM groups g
                        JOIN conversation_members member
                          ON member.conversation_id = g.conversation_id
                         AND member.user_id = :userId
                         AND member.status = 'ACTIVE'
                        LEFT JOIN conversation_members active_member
                          ON active_member.conversation_id = g.conversation_id
                         AND active_member.status = 'ACTIVE'
                        WHERE g.status = 'ACTIVE'
                        GROUP BY g.conversation_id, g.group_no, g.name, g.description,
                                 g.visibility, g.owner_user_id, g.avatar_media_id,
                                 g.avatar_version, member.role
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

    int addMember(UUID conversationId, UUID userId) {
        return jdbc.sql("""
                        INSERT INTO conversation_members (
                            conversation_id, user_id, role, status,
                            history_visible_after_seq, membership_version)
                        VALUES (:conversationId, :userId, 'MEMBER', 'ACTIVE', 0, 1)
                        ON CONFLICT (conversation_id, user_id)
                        DO UPDATE SET role = 'MEMBER',
                                      status = 'ACTIVE',
                                      left_at = NULL,
                                      joined_at = CURRENT_TIMESTAMP,
                                      membership_version = conversation_members.membership_version + 1
                        WHERE conversation_members.status <> 'ACTIVE'
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
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
                row.getInt("member_count"));
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
            int memberCount
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
}
