package com.jitong.im.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("jitong.media")
public record MediaProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        Duration orphanCleanupGrace
) {
    public MediaProperties {
        if (orphanCleanupGrace == null) {
            orphanCleanupGrace = Duration.ofHours(24);
        }
        if (orphanCleanupGrace.isZero() || orphanCleanupGrace.isNegative()) {
            throw new IllegalArgumentException("orphanCleanupGrace must be positive");
        }
    }

    @Override
    public String toString() {
        return "MediaProperties[configured=true]";
    }
}
