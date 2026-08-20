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
                .query(this::mapUser)
                .optional()
                .orElse(null);
    }

    User findActiveUserById(UUID userId) {
        return jdbc.sql("""
                        SELECT id, account_no, display_name, password_hash
                        FROM users
                        WHERE id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .query(this::mapUser)
                .optional()
                .orElse(null);
    }

    DeviceSession findDeviceSession(UUID userId, DeviceClass deviceClass, String installationIdHash) {
        return jdbc.sql("""
                        SELECT id, user_id, device_class
                        FROM devices
                        WHERE user_id = :userId
                          AND device_class = :deviceClass
                          AND installation_id_hash = :installationIdHash
                          AND trust_state = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("deviceClass", deviceClass.name())
                .param("installationIdHash", installationIdHash)
                .query((row, rowNum) -> new DeviceSession(
                        row.getObject("id", UUID.class),
                        row.getObject("user_id", UUID.class),
                        DeviceClass.valueOf(row.getString("device_class"))))
                .optional()
                .orElse(null);
    }

    UUID findActiveDeviceId(UUID userId, DeviceClass deviceClass) {
        return jdbc.sql("""
                        SELECT id
                        FROM devices
                        WHERE user_id = :userId
                          AND device_class = :deviceClass
                          AND trust_state = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .param("deviceClass", deviceClass.name())
                .query(UUID.class)
                .optional()
                .orElse(null);
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

    void insertDevice(
            UUID deviceId,
            UUID userId,
            DeviceClass deviceClass,
            String installationIdHash,
            Instant now
    ) {
        jdbc.sql("""
                        INSERT INTO devices (
                            id, user_id, device_class, installation_id_hash, last_seen_at
                        ) VALUES (
                            :id, :userId, :deviceClass, :installationIdHash, :lastSeenAt
                        )
                        """)
                .param("id", deviceId)
                .param("userId", userId)
                .param("deviceClass", deviceClass.name())
                .param("installationIdHash", installationIdHash)
                .param("lastSeenAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    void touchDevice(UUID deviceId, Instant now) {
        jdbc.sql("""
                        UPDATE devices
                        SET last_seen_at = :lastSeenAt
                        WHERE id = :deviceId AND trust_state = 'ACTIVE'
                        """)
                .param("deviceId", deviceId)
                .param("lastSeenAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    void updatePushToken(UUID deviceId, String token) {
        jdbc.sql("""
                        UPDATE devices
                        SET push_token_ciphertext = :token
                        WHERE id = :deviceId AND trust_state = 'ACTIVE'
                        """)
                .param("deviceId", deviceId)
                .param("token", token)
                .update();
    }

    String findPushToken(UUID deviceId) {
        return jdbc.sql("""
                        SELECT push_token_ciphertext
                        FROM devices
                        WHERE id = :deviceId AND trust_state = 'ACTIVE'
                        """)
                .param("deviceId", deviceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    String findActiveDeviceClass(UUID deviceId) {
        return jdbc.sql("""
                        SELECT device_class
                        FROM devices
                        WHERE id = :deviceId AND trust_state = 'ACTIVE'
                        """)
                .param("deviceId", deviceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    void clearPushToken(UUID deviceId) {
        jdbc.sql("""
                        UPDATE devices
                        SET push_token_ciphertext = NULL
                        WHERE id = :deviceId
                        """)
                .param("deviceId", deviceId)
                .update();
    }

    void insertSession(
            UUID sessionId,
            UUID deviceId,
            UUID userId,
            String accessTokenHash,
            Instant accessTokenExpiresAt,
            String refreshTokenHash,
            UUID refreshTokenId,
            UUID familyId,
            Instant refreshTokenExpiresAt
    ) {
        jdbc.sql("""
                        INSERT INTO auth_sessions (
                            id, user_id, device_id, access_token_hash, expires_at
                        ) VALUES (
                            :id, :userId, :deviceId, :accessTokenHash, :expiresAt
                        )
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("deviceId", deviceId)
                .param("accessTokenHash", accessTokenHash)
                .param("expiresAt", utc(accessTokenExpiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        INSERT INTO refresh_tokens (
                            id, session_id, family_id, token_hash, expires_at
                        ) VALUES (
                            :id, :sessionId, :familyId, :tokenHash, :expiresAt
                        )
                        """)
                .param("id", refreshTokenId)
                .param("sessionId", sessionId)
                .param("familyId", familyId)
                .param("tokenHash", refreshTokenHash)
                .param("expiresAt", utc(refreshTokenExpiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    void insertAccessSession(
            UUID sessionId,
            UUID deviceId,
            UUID userId,
            String accessTokenHash,
            Instant accessTokenExpiresAt
    ) {
        jdbc.sql("""
                        INSERT INTO auth_sessions (
                            id, user_id, device_id, access_token_hash, expires_at
                        ) VALUES (
                            :id, :userId, :deviceId, :accessTokenHash, :expiresAt
                        )
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("deviceId", deviceId)
                .param("accessTokenHash", accessTokenHash)
                .param("expiresAt", utc(accessTokenExpiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    String findDeviceClass(UUID deviceId) {
        return jdbc.sql("SELECT device_class FROM devices WHERE id = :deviceId")
                .param("deviceId", deviceId)
                .query(String.class)
                .single();
    }

    String findUserAccountNo(UUID userId) {
        return jdbc.sql("SELECT account_no FROM users WHERE id = :userId")
                .param("userId", userId)
                .query(String.class)
                .single()
                .trim();
    }

    AuthSession findSessionByAccessTokenHash(String accessTokenHash) {
        return jdbc.sql("""
                        SELECT s.user_id, s.device_id, s.expires_at, s.status, d.trust_state
                        FROM auth_sessions s
                        JOIN devices d ON d.id = s.device_id
                        WHERE s.access_token_hash = :accessTokenHash
                        """)
                .param("accessTokenHash", accessTokenHash)
                .query((row, rowNum) -> new AuthSession(
                        row.getObject("user_id", UUID.class),
                        row.getObject("device_id", UUID.class),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        row.getString("status"),
                        row.getString("trust_state")))
                .optional()
                .orElse(null);
    }

    UUID findUserIdByAccessTokenHash(String accessTokenHash) {
        return jdbc.sql("""
                        SELECT user_id
                        FROM auth_sessions
                        WHERE access_token_hash = :accessTokenHash
                        """)
                .param("accessTokenHash", accessTokenHash)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    void revokeSessionByAccessTokenHash(String accessTokenHash, Instant now) {
        jdbc.sql("""
                        UPDATE auth_sessions
                        SET status = 'REVOKED', revoked_at = :revokedAt
                        WHERE access_token_hash = :accessTokenHash
                          AND status = 'ACTIVE'
                        """)
                .param("accessTokenHash", accessTokenHash)
                .param("revokedAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE refresh_tokens
                        SET state = 'REVOKED'
                        WHERE session_id IN (
                            SELECT id
                            FROM auth_sessions
                            WHERE access_token_hash = :accessTokenHash
                        )
                          AND state <> 'REVOKED'
                        """)
                .param("accessTokenHash", accessTokenHash)
                .update();
    }

    ChallengeRecord findChallenge(String challengeHash) {
        return jdbc.sql("""
                        SELECT id,
                               user_id,
                               replaced_device_id,
                               new_installation_id_hash,
                               device_class,
                               used_at,
                               expires_at
                        FROM login_challenges
                        WHERE challenge_hash = :challengeHash
                        """)
                .param("challengeHash", challengeHash)
                .query(this::mapChallenge)
                .optional()
                .orElse(null);
    }

    void insertReplacementChallenge(
            UUID challengeId,
            UUID userId,
            UUID replacedDeviceId,
            String installationIdHash,
            DeviceClass deviceClass,
            String challengeHash,
            Instant expiresAt
    ) {
        jdbc.sql("""
                        INSERT INTO login_challenges (
                            id, user_id, replaced_device_id, new_installation_id_hash,
                            device_class, challenge_hash, expires_at
                        ) VALUES (
                            :id, :userId, :replacedDeviceId, :installationIdHash,
                            :deviceClass, :challengeHash, :expiresAt
                        )
                        """)
                .param("id", challengeId)
                .param("userId", userId)
                .param("replacedDeviceId", replacedDeviceId)
                .param("installationIdHash", installationIdHash)
                .param("deviceClass", deviceClass.name())
                .param("challengeHash", challengeHash)
                .param("expiresAt", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    ChallengeRecord findChallengeForUpdate(String challengeHash) {
        return jdbc.sql("""
                        SELECT id,
                               user_id,
                               replaced_device_id,
                               new_installation_id_hash,
                               device_class,
                               used_at,
                               expires_at
                        FROM login_challenges
                        WHERE challenge_hash = :challengeHash
                        FOR UPDATE
                        """)
                .param("challengeHash", challengeHash)
                .query(this::mapChallenge)
                .optional()
                .orElse(null);
    }

    ChallengeRecord findChallengeForUpdate(String challengeHash, Instant now) {
        ChallengeRecord challenge = findChallengeForUpdate(challengeHash);
        if (challenge == null || challenge.usedAt() != null || !challenge.expiresAt().isAfter(now)) {
            return null;
        }
        return challenge;
    }

    void markChallengeUsed(UUID challengeId, Instant now) {
        jdbc.sql("""
                        UPDATE login_challenges
                        SET used_at = :usedAt
                        WHERE id = :challengeId
                        """)
                .param("challengeId", challengeId)
                .param("usedAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    void revokeDevice(UUID deviceId, Instant now) {
        jdbc.sql("""
                        UPDATE devices
                        SET trust_state = 'UNTRUSTED',
                            push_token_ciphertext = NULL,
                            untrusted_at = :untrustedAt
                        WHERE id = :deviceId AND trust_state = 'ACTIVE'
                        """)
                .param("deviceId", deviceId)
                .param("untrustedAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        revokeDeviceCredentials(deviceId, now);
    }

    void revokeDeviceCredentials(UUID deviceId, Instant now) {
        jdbc.sql("""
                        UPDATE auth_sessions
                        SET status = 'REVOKED', revoked_at = :revokedAt
                        WHERE device_id = :deviceId AND status = 'ACTIVE'
                        """)
                .param("deviceId", deviceId)
                .param("revokedAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE refresh_tokens
                        SET state = 'REVOKED'
                        WHERE session_id IN (
                            SELECT id FROM auth_sessions WHERE device_id = :deviceId
                        )
                          AND state <> 'REVOKED'
                        """)
                .param("deviceId", deviceId)
                .update();
    }

    void lockDevice(UUID deviceId) {
        jdbc.sql("""
                        SELECT id
                        FROM devices
                        WHERE id = :deviceId
                        FOR UPDATE
                        """)
                .param("deviceId", deviceId)
                .query(UUID.class)
                .single();
    }

    void lockSessions(UUID deviceId) {
        jdbc.sql("""
                        SELECT id
                        FROM auth_sessions
                        WHERE device_id = :deviceId
                        FOR UPDATE
                        """)
                .param("deviceId", deviceId)
                .query(UUID.class)
                .list();
    }

    RefreshTokenRecord findRefreshTokenForUpdate(String tokenHash) {
        return jdbc.sql("""
                        SELECT r.id,
                               r.session_id,
                               s.device_id,
                               s.user_id,
                               r.family_id,
                               r.state,
                               s.status,
                               r.expires_at,
                               d.trust_state
                        FROM refresh_tokens r
                        JOIN auth_sessions s ON s.id = r.session_id
                        JOIN devices d ON d.id = s.device_id
                        WHERE r.token_hash = :tokenHash
                        FOR UPDATE OF r
                        """)
                .param("tokenHash", tokenHash)
                .query((row, rowNum) -> new RefreshTokenRecord(
                        row.getObject("id", UUID.class),
                        row.getObject("session_id", UUID.class),
                        row.getObject("device_id", UUID.class),
                        row.getObject("user_id", UUID.class),
                        row.getObject("family_id", UUID.class),
                        row.getString("state"),
                        row.getString("status"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        row.getString("trust_state")))
                .optional()
                .orElse(null);
    }

    RefreshTokenRecord findRefreshToken(String rawRefreshToken) {
        return jdbc.sql("""
                        SELECT r.id,
                               r.session_id,
                               s.device_id,
                               s.user_id,
                               r.family_id,
                               r.state,
                               s.status,
                               r.expires_at,
                               d.trust_state
                        FROM refresh_tokens r
                        JOIN auth_sessions s ON s.id = r.session_id
                        JOIN devices d ON d.id = s.device_id
                        WHERE r.token_hash = :tokenHash
                        """)
                .param("tokenHash", TokenDigests.sha256(rawRefreshToken))
                .query((row, rowNum) -> new RefreshTokenRecord(
                        row.getObject("id", UUID.class),
                        row.getObject("session_id", UUID.class),
                        row.getObject("device_id", UUID.class),
                        row.getObject("user_id", UUID.class),
                        row.getObject("family_id", UUID.class),
                        row.getString("state"),
                        row.getString("status"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        row.getString("trust_state")))
                .optional()
                .orElse(null);
    }

    int consumeRefreshToken(UUID tokenId) {
        return jdbc.sql("""
                        UPDATE refresh_tokens
                        SET state = 'CONSUMED'
                        WHERE id = :tokenId AND state = 'ACTIVE'
                        """)
                .param("tokenId", tokenId)
                .update();
    }

    void insertRotatedRefreshToken(
            UUID tokenId,
            UUID sessionId,
            UUID parentId,
            UUID familyId,
            String tokenHash,
            Instant expiresAt
    ) {
        jdbc.sql("""
                        INSERT INTO refresh_tokens (
                            id, session_id, family_id, token_hash, parent_id, expires_at
                        ) VALUES (
                            :id, :sessionId, :familyId, :tokenHash, :parentId, :expiresAt
                        )
                        """)
                .param("id", tokenId)
                .param("sessionId", sessionId)
                .param("familyId", familyId)
                .param("tokenHash", tokenHash)
                .param("parentId", parentId)
                .param("expiresAt", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    void rotateRefreshToken(
            UUID parentId,
            UUID newSessionId,
            UUID familyId,
            String refreshTokenHash,
            Instant refreshTokenExpiresAt,
            UUID accessSessionId,
            UUID deviceId,
            UUID userId,
            String accessTokenHash,
            Instant accessTokenExpiresAt
    ) {
        if (consumeRefreshToken(parentId) != 1) {
            throw new RefreshTokenException();
        }
        insertAccessSession(
                accessSessionId,
                deviceId,
                userId,
                accessTokenHash,
                accessTokenExpiresAt);
        insertRotatedRefreshToken(
                UuidV7.random(),
                newSessionId,
                parentId,
                familyId,
                refreshTokenHash,
                refreshTokenExpiresAt);
    }

    void revokeFamily(UUID familyId, UUID deviceId, Instant now) {
        jdbc.sql("""
                        UPDATE devices
                        SET trust_state = 'UNTRUSTED',
                            push_token_ciphertext = NULL,
                            untrusted_at = :untrustedAt
                        WHERE id = :deviceId AND trust_state = 'ACTIVE'
                        """)
                .param("deviceId", deviceId)
                .param("untrustedAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE auth_sessions
                        SET status = 'REVOKED', revoked_at = :revokedAt
                        WHERE device_id = :deviceId AND status = 'ACTIVE'
                        """)
                .param("deviceId", deviceId)
                .param("revokedAt", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE refresh_tokens
                        SET state = 'REVOKED'
                        WHERE family_id = :familyId AND state <> 'REVOKED'
                        """)
                .param("familyId", familyId)
                .update();
    }

    UserRetirementResult retireUser(UUID userId, Instant retiredAt) {
        int retired = jdbc.sql("""
                        UPDATE users
                        SET status = 'RETIRED', retired_at = :retiredAt
                        WHERE id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("retiredAt", utc(retiredAt), Types.TIMESTAMP_WITH_TIMEZONE)
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
                .param("retiredAt", utc(retiredAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE auth_sessions
                        SET status = 'REVOKED', revoked_at = :retiredAt
                        WHERE user_id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("retiredAt", utc(retiredAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("""
                        UPDATE refresh_tokens
                        SET state = 'REVOKED'
                        WHERE session_id IN (
                            SELECT id FROM auth_sessions WHERE user_id = :userId
                        )
                          AND state <> 'REVOKED'
                        """)
                .param("userId", userId)
                .update();
        jdbc.sql("""
                        UPDATE devices
                        SET trust_state = 'UNTRUSTED',
                            push_token_ciphertext = NULL,
                            untrusted_at = :retiredAt
                        WHERE user_id = :userId AND trust_state = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("retiredAt", utc(retiredAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return UserRetirementResult.RETIRED;
    }

    private OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private User mapUser(java.sql.ResultSet row, int rowNum) throws java.sql.SQLException {
        return new User(
                row.getObject("id", UUID.class),
                row.getString("account_no").trim(),
                row.getString("display_name"),
                row.getString("password_hash"));
    }

    private ChallengeRecord mapChallenge(java.sql.ResultSet row, int rowNum) throws java.sql.SQLException {
        OffsetDateTime usedAt = row.getObject("used_at", OffsetDateTime.class);
        return new ChallengeRecord(
                row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class),
                row.getObject("replaced_device_id", UUID.class),
                row.getString("new_installation_id_hash"),
                DeviceClass.valueOf(row.getString("device_class")),
                usedAt == null ? null : usedAt.toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    record ChallengeRecord(
            UUID id,
            UUID userId,
            UUID replacedDeviceId,
            String installationIdHash,
            DeviceClass deviceClass,
            Instant usedAt,
            Instant expiresAt
    ) {
    }
}
