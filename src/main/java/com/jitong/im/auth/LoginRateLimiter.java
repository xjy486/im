package com.jitong.im.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
class LoginRateLimiter {

    private final AuthProperties.LoginRateLimit properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    LoginRateLimiter(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LoginRateLimiter(AuthProperties properties, Clock clock) {
        this.properties = properties.loginRateLimit();
        this.clock = clock;
    }

    void check(String accountNo, String ipAddress) {
        Instant now = clock.instant();
        if (isBlocked(key("account", accountNo), now) || isBlocked(key("ip", ipAddress), now)) {
            throw new RateLimitExceededException();
        }
    }

    void recordFailure(String accountNo, String ipAddress) {
        record(key("account", accountNo));
        record(key("ip", ipAddress));
    }

    void recordSuccess(String accountNo, String ipAddress) {
        windows.remove(key("account", accountNo));
        windows.remove(key("ip", ipAddress));
    }

    private boolean isBlocked(String key, Instant now) {
        Window window = windows.get(key);
        if (window == null) {
            return false;
        }
        if (window.blockedUntil().isAfter(now)) {
            return true;
        }
        if (!window.blockedUntil().isAfter(Instant.EPOCH)) {
            return false;
        }
        windows.remove(key, window);
        return false;
    }

    private void record(String key) {
        Instant now = clock.instant();
        windows.compute(key, (ignored, current) -> {
            Window window = current;
            if (window == null || !window.windowStartedAt().plus(properties.window()).isAfter(now)) {
                window = new Window(now, 0, Instant.EPOCH);
            }
            int failures = window.failures() + 1;
            Instant blockedUntil = failures >= properties.maxFailures()
                    ? now.plus(properties.block())
                    : window.blockedUntil();
            Window next = new Window(window.windowStartedAt(), failures, blockedUntil);
            return next;
        });
    }

    private String key(String dimension, String value) {
        return dimension + ':' + value;
    }

    private record Window(
            Instant windowStartedAt,
            int failures,
            Instant blockedUntil
    ) {
    }
}
