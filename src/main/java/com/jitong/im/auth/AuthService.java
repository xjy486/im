package com.jitong.im.auth;

import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.AuditSubjectType;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
class AuthService {

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

    @Transactional
    LoginResponse login(String accountNo, String password, String ipAddress, UUID requestId) {
        try {
            rateLimiter.check(accountNo, ipAddress);
        } catch (RateLimitExceededException exception) {
            recordLogin(null, requestId, AuditOutcome.REJECTED, ApiErrorDefinition.RATE_LIMITED);
            throw exception;
        }
        User user = repository.findActiveUser(accountNo);
        if (user == null || !passwordEncoder.matches(password, user.passwordHash())) {
            rateLimiter.recordFailure(accountNo, ipAddress);
            recordLogin(user, requestId, AuditOutcome.REJECTED, ApiErrorDefinition.AUTH_INVALID);
            throw new InvalidCredentialsException();
        }

        rateLimiter.recordSuccess(accountNo, ipAddress);
        Instant now = clock.instant();
        String accessToken = TokenDigests.newOpaqueToken();
        String refreshToken = TokenDigests.newOpaqueToken();
        Instant accessExpiresAt = now.plus(properties.accessTokenLifetime());
        Instant refreshExpiresAt = now.plus(properties.refreshTokenLifetime());
        repository.insertSession(
                UuidV7.random(),
                user.id(),
                TokenDigests.sha256(accessToken),
                accessExpiresAt,
                TokenDigests.sha256(refreshToken),
                UuidV7.random(),
                refreshExpiresAt);
        recordLogin(user, requestId, AuditOutcome.SUCCEEDED, null);
        return LoginResponse.of(user, accessToken, refreshToken, accessExpiresAt, refreshExpiresAt);
    }

    User requireUser(String authorizationHeader) {
        String accessToken = bearerToken(authorizationHeader);
        AuthSession session = repository.findSessionByAccessTokenHash(TokenDigests.sha256(accessToken));
        if (session == null || !"ACTIVE".equals(session.status())) {
            throw new InvalidCredentialsException();
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            throw new ExpiredAccessTokenException();
        }
        return new User(session.userId(), null, null, null);
    }

    @Transactional
    void retireUser(UUID userId) {
        repository.retireUser(userId, clock.instant());
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.startsWith("Bearer ")
                || authorizationHeader.length() <= "Bearer ".length()) {
            throw new InvalidCredentialsException();
        }
        return authorizationHeader.substring("Bearer ".length());
    }

    private void recordLogin(
            User user,
            UUID requestId,
            AuditOutcome outcome,
            ApiErrorDefinition error
    ) {
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.LOGIN,
                outcome,
                user == null ? null : user.id(),
                null,
                user == null ? null : AuditSubjectType.USER,
                user == null ? null : user.id(),
                requestId,
                error,
                clock.instant()));
    }
}
