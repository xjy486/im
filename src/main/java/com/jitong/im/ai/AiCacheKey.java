package com.jitong.im.ai;

import com.jitong.im.auth.TokenDigests;

import java.util.UUID;

final class AiCacheKey {

    private AiCacheKey() {
    }

    static String summary(
            UUID ownerUserId,
            UUID conversationId,
            long fromSeq,
            long toSeq,
            String provider,
            String model,
            String promptVersion,
            boolean imageInputEnabled,
            String contextDigest
    ) {
        return TokenDigests.sha256(String.join("\u001f",
                ownerUserId.toString(),
                "SUMMARY",
                conversationId.toString(),
                Long.toString(fromSeq),
                Long.toString(toSeq),
                provider,
                model,
                promptVersion,
                Boolean.toString(imageInputEnabled),
                contextDigest));
    }
}
