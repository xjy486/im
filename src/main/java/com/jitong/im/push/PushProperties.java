package com.jitong.im.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jitong.push")
public record PushProperties(
        boolean enabled,
        String projectId,
        String serviceAccountJson,
        String tokenEncryptionKey
) {
}
