package com.jitong.im.auth;

import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.AuditSubjectType;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final PublicNumberGenerator publicNumberGenerator;
    private final AuthProperties properties;
    private final LoginRateLimiter rateLimiter;
    private final SecurityAuditSink auditSink;
    private final Clock clock;

    @Autowired
    AuthService(
            AuthRepository repository,
            PasswordEncoder passwordEncoder,
            PublicNumberGenerator publicNumberGenerator,
            AuthProperties properties,
            LoginRateLimiter rateLimiter,
            SecurityAuditSink auditSink
    ) {
        this(
                repository,
                passwordEncoder,
                publicNumberGenerator,
                properties,
                rateLimiter,
                auditSink,
                Clock.systemUTC());
    }

    AuthService(
            AuthRepository repository,
            PasswordEncoder passwordEncoder,
            PublicNumberGenerator publicNumberGenerator,
            AuthProperties properties,
            LoginRateLimiter rateLimiter,
            SecurityAuditSink auditSink,
            Clock clock
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.publicNumberGenerator = publicNumberGenerator;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.auditSink = auditSink;
        this.clock = clock;
    }

    @Transactional
    User createPresetUser(String displayName, String password) {
        return repository.insertUser(displayName, passwordEncoder.encode(password), publicNumberGenerator);
    }

    @Transactional(noRollbackFor = DeviceReplacementRequiredException.class)
    LoginResponse login(
            String accountNo,
            String password,
            String ipAddress,
            UUID requestId,
            String requestedDeviceClass,
            String requestedInstallationId
    ) {
        try {
            rateLimiter.check(accountNo, ipAddress);
        } catch (RateLimitExceededException exception) {
            recordLogin(null, requestId, AuditOutcome.REJECTED, ApiErrorDefinition.RATE_LIMITED, null);
            throw exception;
        }
        User user = repository.findActiveUser(accountNo);
        if (user == null || !passwordEncoder.matches(password, user.passwordHash())) {
            rateLimiter.recordFailure(accountNo, ipAddress);
            recordLogin(user, requestId, AuditOutcome.REJECTED, ApiErrorDefinition.AUTH_INVALID, null);
            throw new InvalidCredentialsException();
        }

        rateLimiter.recordSuccess(accountNo, ipAddress);
        DeviceClass deviceClass = DeviceClass.fromNullable(requestedDeviceClass);
        String installationIdHash = TokenDigests.sha256(normalizeInstallationId(requestedInstallationId));
        Instant now = clock.instant();
        repository.lockUser(user.id());
        DeviceSession device = repository.findDeviceSession(user.id(), deviceClass, installationIdHash);
        if (device == null) {
            UUID activeDeviceId = repository.findActiveDeviceId(user.id(), deviceClass);
            if (activeDeviceId != null) {
                String challenge = TokenDigests.newOpaqueToken();
                repository.insertReplacementChallenge(
                        UuidV7.random(),
                        user.id(),
                        activeDeviceId,
                        installationIdHash,
                        deviceClass,
                        TokenDigests.sha256(challenge),
                        now.plus(Duration.ofMinutes(5)));
                recordLogin(
                        user,
                        requestId,
                        AuditOutcome.REJECTED,
                        ApiErrorDefinition.DEVICE_REPLACEMENT_REQUIRED,
                        activeDeviceId);
                throw new DeviceReplacementRequiredException(challenge, deviceClass);
            }
            device = new DeviceSession(UuidV7.random(), user.id(), deviceClass);
            repository.insertDevice(device.deviceId(), user.id(), deviceClass, installationIdHash, now);
        } else {
            repository.touchDevice(device.deviceId(), now);
        }

        TokenPair tokens = issueTokens(device.deviceId(), user.id(), deviceClass, now);
        recordLogin(user, requestId, AuditOutcome.SUCCEEDED, null, device.deviceId());
        return LoginResponse.of(
                user,
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessTokenExpiresAt(),
                tokens.refreshTokenExpiresAt(),
                tokens.deviceId(),
                tokens.deviceClass());
    }

    @Transactional
    LoginResponse confirmReplacement(String rawChallenge, UUID requestId) {
        Instant now = clock.instant();
        String challengeHash = TokenDigests.sha256(rawChallenge);
        AuthRepository.ChallengeRecord challenge = repository.findChallenge(challengeHash);
        if (challenge == null
                || challenge.usedAt() != null
                || !challenge.expiresAt().isAfter(now)) {
            throw new InvalidCredentialsException();
        }

        repository.lockUser(challenge.userId());
        challenge = repository.findChallengeForUpdate(challengeHash, now);
        if (challenge == null
                || !challenge.expiresAt().isAfter(now)) {
            throw new InvalidCredentialsException();
        }
        UUID activeDeviceId = repository.findActiveDeviceId(
                challenge.userId(),
                challenge.deviceClass());
        if (!challenge.replacedDeviceId().equals(activeDeviceId)) {
            throw new InvalidCredentialsException();
        }
        repository.revokeDevice(challenge.replacedDeviceId(), now);
        UUID deviceId = UuidV7.random();
        repository.insertDevice(
                deviceId,
                challenge.userId(),
                challenge.deviceClass(),
                challenge.installationIdHash(),
                now);
        repository.markChallengeUsed(challenge.id(), now);
        User user = repository.findActiveUserById(challenge.userId());
        if (user == null) {
            throw new InvalidCredentialsException();
        }

        TokenPair tokens = issueTokens(deviceId, user.id(), challenge.deviceClass(), now);
        recordDeviceReplacement(
                user.id(),
                challenge.replacedDeviceId(),
                requestId,
                AuditOutcome.SUCCEEDED,
                null);
        return LoginResponse.of(
                user,
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessTokenExpiresAt(),
                tokens.refreshTokenExpiresAt(),
                tokens.deviceId(),
                tokens.deviceClass());
    }

    @Transactional(noRollbackFor = RefreshTokenException.class)
    LoginResponse refresh(String rawRefreshToken, UUID requestId) {
        Instant now = clock.instant();
        RefreshTokenRecord token = repository.findRefreshToken(rawRefreshToken);
        if (token == null) {
            throw new RefreshTokenException();
        }
        repository.lockUser(token.userId());
        repository.lockDevice(token.deviceId());
        repository.lockSessions(token.deviceId());
        token = repository.findRefreshTokenForUpdate(TokenDigests.sha256(rawRefreshToken));
        if (token == null
                || !token.expiresAt().isAfter(now)
                || !"ACTIVE".equals(token.state())
                || !"ACTIVE".equals(token.sessionStatus())
                || !"ACTIVE".equals(token.deviceTrustState())) {
            if (token != null && ("CONSUMED".equals(token.state())
                    || !"ACTIVE".equals(token.sessionStatus())
                    || !"ACTIVE".equals(token.deviceTrustState()))) {
                repository.revokeFamily(token.familyId(), token.deviceId(), now);
                recordTokenReplay(
                        token.userId(),
                        token.deviceId(),
                        requestId,
                        AuditOutcome.REJECTED,
                        ApiErrorDefinition.AUTH_INVALID);
            }
            throw new RefreshTokenException();
        }

        String accessToken = TokenDigests.newOpaqueToken();
        String refreshToken = TokenDigests.newOpaqueToken();
        Instant accessExpiresAt = now.plus(properties.accessTokenLifetime());
        Instant refreshExpiresAt = token.expiresAt();
        UUID newSessionId = UuidV7.random();
        repository.rotateRefreshToken(
                token.tokenId(),
                newSessionId,
                token.familyId(),
                TokenDigests.sha256(refreshToken),
                refreshExpiresAt,
                newSessionId,
                token.deviceId(),
                token.userId(),
                TokenDigests.sha256(accessToken),
                accessExpiresAt);
        recordLogin(
                new User(token.userId(), null, null, null),
                requestId,
                AuditOutcome.SUCCEEDED,
                null,
                token.deviceId());
        return new LoginResponse(
                1,
                token.userId(),
                repository.findUserAccountNo(token.userId()),
                accessToken,
                refreshToken,
                accessExpiresAt,
                refreshExpiresAt,
                token.deviceId(),
                repository.findDeviceClass(token.deviceId()));
    }

    User requireUser(String authorizationHeader) {
        AuthSession session = requireSession(authorizationHeader);
        return new User(session.userId(), null, null, null);
    }

    public UUID requireUserId(String authorizationHeader) {
        return requireUser(authorizationHeader).id();
    }

    public AuthenticatedDevice requireAuthenticatedDevice(String authorizationHeader) {
        AuthSession session = requireSession(authorizationHeader);
        return new AuthenticatedDevice(
                session.userId(),
                session.deviceId(),
                session.sessionId(),
                repository.findDeviceClass(session.deviceId()));
    }

    @Transactional
    void logout(String authorizationHeader, UUID requestId) {
        String accessToken = bearerToken(authorizationHeader);
        UUID userId = repository.findUserIdByAccessTokenHash(TokenDigests.sha256(accessToken));
        if (userId == null) {
            throw new InvalidCredentialsException();
        }
        AuthSession session = repository.findSessionByAccessTokenHash(
                TokenDigests.sha256(accessToken));
        repository.revokeSessionByAccessTokenHash(
                TokenDigests.sha256(accessToken),
                clock.instant());
        if (session != null) {
            repository.clearPushToken(session.deviceId());
        }
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.LOGOUT,
                AuditOutcome.SUCCEEDED,
                userId,
                null,
                AuditSubjectType.USER,
                userId,
                requestId,
                null,
                clock.instant()));
    }

    @Transactional
    void retireUser(UUID userId, UUID requestId) {
        UserRetirementResult result = repository.retireUser(userId, clock.instant());
        if (result != UserRetirementResult.RETIRED) {
            ApiErrorDefinition error = result == UserRetirementResult.NOT_FOUND
                    ? ApiErrorDefinition.USER_NOT_FOUND
                    : ApiErrorDefinition.CONFLICT;
            recordRetirement(userId, requestId, AuditOutcome.REJECTED, error);
            throw new UserRetirementException(result);
        }
        recordRetirement(userId, requestId, AuditOutcome.SUCCEEDED, null);
    }

    private TokenPair issueTokens(UUID deviceId, UUID userId, DeviceClass deviceClass, Instant now) {
        String accessToken = TokenDigests.newOpaqueToken();
        String refreshToken = TokenDigests.newOpaqueToken();
        Instant accessExpiresAt = now.plus(properties.accessTokenLifetime());
        Instant refreshExpiresAt = now.plus(properties.refreshTokenLifetime());
        repository.insertSession(
                UuidV7.random(),
                deviceId,
                userId,
                TokenDigests.sha256(accessToken),
                accessExpiresAt,
                TokenDigests.sha256(refreshToken),
                UuidV7.random(),
                UuidV7.random(),
                refreshExpiresAt);
        return new TokenPair(
                deviceId,
                deviceClass.name(),
                accessToken,
                refreshToken,
                accessExpiresAt,
                refreshExpiresAt);
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.startsWith("Bearer ")
                || authorizationHeader.length() <= "Bearer ".length()) {
            throw new InvalidCredentialsException();
        }
        return authorizationHeader.substring("Bearer ".length());
    }

    private AuthSession requireSession(String authorizationHeader) {
        String accessToken = bearerToken(authorizationHeader);
        AuthSession session = repository.findSessionByAccessTokenHash(TokenDigests.sha256(accessToken));
        if (session == null
                || !"ACTIVE".equals(session.status())
                || !"ACTIVE".equals(session.deviceTrustState())) {
            throw new InvalidCredentialsException();
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            throw new ExpiredAccessTokenException();
        }
        return session;
    }

    private String normalizeInstallationId(String installationId) {
        return installationId == null || installationId.isBlank()
                ? "legacy-installation"
                : installationId.trim();
    }

    private void recordLogin(
            User user,
            UUID requestId,
            AuditOutcome outcome,
            ApiErrorDefinition error,
            UUID deviceId
    ) {
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.LOGIN,
                outcome,
                user == null ? null : user.id(),
                deviceId,
                user == null ? null : AuditSubjectType.USER,
                user == null ? null : user.id(),
                requestId,
                error,
                clock.instant()));
    }

    private void recordDeviceReplacement(
            UUID userId,
            UUID deviceId,
            UUID requestId,
            AuditOutcome outcome,
            ApiErrorDefinition error
    ) {
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.DEVICE_REPLACEMENT,
                outcome,
                userId,
                deviceId,
                AuditSubjectType.DEVICE,
                deviceId,
                requestId,
                error,
                clock.instant()));
    }

    private void recordTokenReplay(
            UUID userId,
            UUID deviceId,
            UUID requestId,
            AuditOutcome outcome,
            ApiErrorDefinition error
    ) {
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.TOKEN_REPLAY,
                outcome,
                userId,
                deviceId,
                AuditSubjectType.DEVICE,
                deviceId,
                requestId,
                error,
                clock.instant()));
    }

    private void recordRetirement(
            UUID userId,
            UUID requestId,
            AuditOutcome outcome,
            ApiErrorDefinition error
    ) {
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.ACCOUNT_DELETION,
                outcome,
                null,
                null,
                AuditSubjectType.USER,
                userId,
                requestId,
                error,
                clock.instant()));
    }
}
