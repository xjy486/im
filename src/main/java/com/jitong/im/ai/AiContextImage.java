package com.jitong.im.ai;

import java.util.UUID;

public record AiContextImage(
        UUID messageId,
        UUID mediaId,
        String contentType,
        int width,
        int height,
        byte[] content
) {

    public AiContextImage {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
