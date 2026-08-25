package com.jitong.im.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationRateLimiterTest {

    @Test
    void blocks_the_sixth_registration_from_the_same_ip_within_the_window() {
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(
                new RegistrationProperties(5, Duration.ofMinutes(1), Duration.ofMinutes(1)),
                Clock.fixed(Instant.parse("2026-08-25T09:00:00Z"), ZoneOffset.UTC));

        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.acquire("192.0.2.10");
        }

        assertThatThrownBy(() -> limiter.acquire("192.0.2.10"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void keeps_separate_limits_for_different_ips() {
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(
                new RegistrationProperties(1, Duration.ofMinutes(1), Duration.ofMinutes(1)),
                Clock.fixed(Instant.parse("2026-08-25T09:00:00Z"), ZoneOffset.UTC));

        limiter.acquire("192.0.2.10");

        assertThatThrownBy(() -> limiter.acquire("192.0.2.10"))
                .isInstanceOf(RateLimitExceededException.class);
        limiter.acquire("192.0.2.11");
    }
}
