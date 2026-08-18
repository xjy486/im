package com.jitong.im.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("jitong.auth")
public record AuthProperties(
        Duration accessTokenLifetime,
        Duration refreshTokenLifetime,
        LoginRateLimit loginRateLimit
) {
    public record LoginRateLimit(
            int maxFailures,
            Duration window,
            Duration block
    ) {
    }
}
