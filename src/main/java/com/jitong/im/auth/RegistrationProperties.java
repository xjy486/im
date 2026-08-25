package com.jitong.im.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("jitong.auth.registration-rate-limit")
public record RegistrationProperties(
        int maxRegistrations,
        Duration window,
        Duration block
) {
}
