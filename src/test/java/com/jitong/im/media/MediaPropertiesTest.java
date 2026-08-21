package com.jitong.im.media;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MediaPropertiesTest {

    @Test
    void defaults_orphan_cleanup_grace_when_constructed_without_one() {
        MediaProperties properties = new MediaProperties(
                "http://localhost:9000",
                "access",
                "secret",
                "bucket",
                null);

        assertThat(properties.orphanCleanupGrace()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void rejects_non_positive_orphan_cleanup_grace() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MediaProperties(
                        "http://localhost:9000",
                        "access",
                        "secret",
                        "bucket",
                        Duration.ZERO));
    }
}
