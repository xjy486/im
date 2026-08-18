package com.jitong.im.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    @Test
    void limits_failures_by_both_account_and_ip() {
        AuthProperties properties = new AuthProperties(
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                new AuthProperties.LoginRateLimit(2, Duration.ofMinutes(1), Duration.ofMinutes(1)));
        LoginRateLimiter limiter = new LoginRateLimiter(
                properties,
                Clock.fixed(Instant.parse("2026-08-18T05:00:00Z"), ZoneOffset.UTC));

        limiter.check("12345678906", "192.0.2.10");
        limiter.recordFailure("12345678906", "192.0.2.10");
        limiter.recordFailure("12345678906", "192.0.2.10");

        assertThatThrownBy(() -> limiter.check("12345678906", "192.0.2.10"))
                .isInstanceOf(RateLimitExceededException.class);
    }
}
