package com.jitong.im.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jitong.media")
public record MediaProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket
) {
    @Override
    public String toString() {
        return "MediaProperties[configured=true]";
    }
}
