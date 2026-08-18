package com.jitong.im.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
class AuthRepository {

    private final JdbcClient jdbc;

    AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    User insertUser(String displayName, String passwordHash, PublicNumberGenerator numbers) {
        UUID userId = UuidV7.random();
        for (int attempt = 0; attempt < 10; attempt++) {
            String accountNo = numbers.next();
            int claimed = jdbc.sql("""
                            INSERT INTO public_identifiers (public_no, entity_type, entity_id)
                            VALUES (:publicNo, 'USER', :entityId)
                            ON CONFLICT (public_no) DO NOTHING
                            """)
                    .param("publicNo", accountNo)
                    .param("entityId", userId)
                    .update();
            if (claimed == 0) {
                continue;
            }

            int inserted = jdbc.sql("""
                            INSERT INTO users (id, account_no, display_name, password_hash)
                            VALUES (:id, :accountNo, :displayName, :passwordHash)
                            ON CONFLICT (account_no) DO NOTHING
                            """)
                    .param("id", userId)
                    .param("accountNo", accountNo)
                    .param("displayName", displayName)
                    .param("passwordHash", passwordHash)
                    .update();
            if (inserted == 0) {
                jdbc.sql("""
                                DELETE FROM public_identifiers
                                WHERE public_no = :publicNo AND entity_id = :entityId
                                """)
                        .param("publicNo", accountNo)
                        .param("entityId", userId)
                        .update();
                continue;
            }
            return new User(userId, accountNo, displayName, passwordHash);
        }
        throw new IllegalStateException("Could not allocate a public account number");
    }

    User findActiveUser(String accountNo) {
        return jdbc.sql("""
                        SELECT id, account_no, display_name, password_hash
                        FROM users
                        WHERE account_no = :accountNo AND status = 'ACTIVE'
                        """)
                .param("accountNo", accountNo)
                .query((row, rowNum) -> new User(
                        row.getObject("id", UUID.class),
                        row.getString("account_no").trim(),
                        row.getString("display_name"),
                        row.getString("password_hash")))
                .optional()
                .orElse(null);
    }

    void insertSession(
            UUID sessionId,
            UUID userId,
            String accessTokenHash,
            Instant accessTokenExpiresAt,
            String refreshTokenHash,
            UUID familyId,
            Instant refreshTokenExpiresAt
    ) {
        OffsetDateTime accessExpires = OffsetDateTime.ofInstant(accessTokenExpiresAt, ZoneOffset.UTC);
        OffsetDateTime refreshExpires = OffsetDateTime.ofInstant(refreshTokenExpiresAt, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO auth_sessions (id, user_id, access_token_hash, expires_at)
                        VALUES (:id, :userId, :accessTokenHash, :expiresAt)
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("accessTokenHash", accessTokenHash)
                .param("expiresAt", accessExpires, Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        INSERT INTO refresh_tokens (id, session_id, family_id, token_hash, expires_at)
                        VALUES (:id, :sessionId, :familyId, :tokenHash, :expiresAt)
                        """)
                .param("id", UuidV7.random())
                .param("sessionId", sessionId)
                .param("familyId", familyId)
                .param("tokenHash", refreshTokenHash)
                .param("expiresAt", refreshExpires, Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    AuthSession findSessionByAccessTokenHash(String accessTokenHash) {
        return jdbc.sql("""
                        SELECT user_id, expires_at, status
                        FROM auth_sessions
                        WHERE access_token_hash = :accessTokenHash
                        """)
                .param("accessTokenHash", accessTokenHash)
                .query((row, rowNum) -> new AuthSession(
                        row.getObject("user_id", UUID.class),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        row.getString("status")))
                .optional()
                .orElse(null);
    }

    UserRetirementResult retireUser(UUID userId, Instant retiredAt) {
        int retired = jdbc.sql("""
                        UPDATE users
                        SET status = 'RETIRED', retired_at = :retiredAt
                        WHERE id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("retiredAt", OffsetDateTime.ofInstant(retiredAt, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        if (retired == 0) {
            String status = jdbc.sql("SELECT status FROM users WHERE id = :userId")
                    .param("userId", userId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            return status == null
                    ? UserRetirementResult.NOT_FOUND
                    : UserRetirementResult.ALREADY_RETIRED;
        }
        jdbc.sql("""
                        UPDATE public_identifiers
                        SET retired_at = :retiredAt
                        WHERE entity_type = 'USER' AND entity_id = :userId AND retired_at IS NULL
                        """)
                .param("userId", userId)
                .param("retiredAt", OffsetDateTime.ofInstant(retiredAt, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE auth_sessions
                        SET status = 'REVOKED', revoked_at = :retiredAt
                        WHERE user_id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("retiredAt", OffsetDateTime.ofInstant(retiredAt, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE refresh_tokens
                        SET state = 'REVOKED'
                        WHERE session_id IN (
                            SELECT id
                            FROM auth_sessions
                            WHERE user_id = :userId
                        )
                          AND state <> 'REVOKED'
                        """)
                .param("userId", userId)
                .update();
        return UserRetirementResult.RETIRED;
    }
}
