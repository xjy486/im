package com.jitong.im.ai;

import com.jitong.im.auth.TokenDigests;

import java.util.List;

final class AiContextDigest {

    private AiContextDigest() {
    }

    static String sha256(List<AiContextMessage> messages, boolean imageInputEnabled) {
        StringBuilder value = new StringBuilder();
        for (AiContextMessage message : messages) {
            value.append(message.messageId()).append('\u001f')
                    .append(message.conversationSeq()).append('\u001f')
                    .append(message.senderId()).append('\u001f')
                    .append(message.text());
            if ("IMAGE".equals(message.type())) {
                value.append('\u001f').append("IMAGE");
            }
            if (imageInputEnabled && message.hasAuthorizedImageReference()) {
                value.append('\u001f')
                        .append(message.mediaId()).append('\u001f')
                        .append(message.mediaSha256());
            }
            value.append('\u001e');
        }
        return TokenDigests.sha256(value.toString());
    }

    static String sha256(List<AiContextMessage> messages) {
        return sha256(messages, false);
    }
}
