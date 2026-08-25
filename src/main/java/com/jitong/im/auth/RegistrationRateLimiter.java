package com.jitong.im.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
class RegistrationRateLimiter {

    private final RegistrationProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    RegistrationRateLimiter(RegistrationProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RegistrationRateLimiter(RegistrationProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    synchronized void acquire(String ipAddress) {
        Instant now = clock.instant();
        String key = normalize(ipAddress);
        Window window = windows.get(key);
        if (window != null && window.blockedUntil().isAfter(now)) {
            throw new RateLimitExceededException();
        }
        if (window == null || !window.windowStartedAt().plus(properties.window()).isAfter(now)) {
            window = new Window(now, 0, Instant.EPOCH);
        }
        int registrations = window.registrations() + 1;
        Instant blockedUntil = registrations >= properties.maxRegistrations()
                ? now.plus(properties.block())
                : window.blockedUntil();
        windows.put(key, new Window(window.windowStartedAt(), registrations, blockedUntil));
    }

    private String normalize(String ipAddress) {
        return ipAddress == null || ipAddress.isBlank() ? "unknown" : ipAddress;
    }

    private record Window(
            Instant windowStartedAt,
            int registrations,
            Instant blockedUntil
    ) {
    }
}
