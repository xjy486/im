package com.jitong.im.ai;

import com.jitong.im.auth.TokenDigests;

import java.util.List;

final class AiContextDigest {

    private AiContextDigest() {
    }

    static String sha256(List<AiContextMessage> messages) {
        StringBuilder value = new StringBuilder();
        for (AiContextMessage message : messages) {
            value.append(message.messageId()).append('\u001f')
                    .append(message.conversationSeq()).append('\u001f')
                    .append(message.senderId()).append('\u001f')
                    .append(message.text()).append('\u001e');
        }
        return TokenDigests.sha256(value.toString());
    }
}
