package com.jitong.im.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jitong.admin")
public record AdminProperties(String apiKey) {
}
