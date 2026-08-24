package com.jitong.im.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiContextDigestTest {

    @Test
    void media_content_hash_changes_the_digest_only_when_image_input_is_enabled() {
        UUID messageId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        AiContextMessage first = new AiContextMessage(
                messageId,
                1,
                senderId,
                "IMAGE",
                "[图片]",
                mediaId,
                "a".repeat(64));
        AiContextMessage changed = new AiContextMessage(
                messageId,
                1,
                senderId,
                "IMAGE",
                "[图片]",
                mediaId,
                "b".repeat(64));

        assertThat(AiContextDigest.sha256(List.of(first), false))
                .isEqualTo(AiContextDigest.sha256(List.of(changed), false));
        assertThat(AiContextDigest.sha256(List.of(first), true))
                .isNotEqualTo(AiContextDigest.sha256(List.of(changed), true));
    }
}
