package com.jitong.im.contact;

import com.jitong.im.auth.RateLimitExceededException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
class ContactRateLimiter {

    private static final int USER_LIMIT = 60;
    private static final int IP_LIMIT = 120;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    ContactRateLimiter() {
        this(Clock.systemUTC());
    }

    ContactRateLimiter(Clock clock) {
        this.clock = clock;
    }

    void check(String userKey, String ipAddress) {
        Instant now = clock.instant();
        if (exceeded("user:" + userKey, USER_LIMIT, now)
                || exceeded("ip:" + ipAddress, IP_LIMIT, now)) {
            throw new RateLimitExceededException();
        }
    }

    void record(String userKey, String ipAddress) {
        record("user:" + userKey, USER_LIMIT);
        record("ip:" + ipAddress, IP_LIMIT);
    }

    private boolean exceeded(String key, int limit, Instant now) {
        Window window = windows.get(key);
        if (window == null || !window.startedAt().plus(WINDOW).isAfter(now)) {
            return false;
        }
        return window.count() >= limit;
    }

    private void record(String key, int limit) {
        Instant now = clock.instant();
        windows.compute(key, (ignored, current) -> {
            Window window = current;
            if (window == null || !window.startedAt().plus(WINDOW).isAfter(now)) {
                window = new Window(now, 0);
            }
            return new Window(window.startedAt(), Math.min(limit, window.count() + 1));
        });
    }

    private record Window(Instant startedAt, int count) {
    }
}
