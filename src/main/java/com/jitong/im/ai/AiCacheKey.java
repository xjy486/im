package com.jitong.im.ai;

import com.jitong.im.auth.TokenDigests;

import java.util.UUID;

final class AiCacheKey {

    private AiCacheKey() {
    }

    static String forContext(
            UUID ownerUserId,
            String kind,
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
                kind,
                conversationId.toString(),
                Long.toString(fromSeq),
                Long.toString(toSeq),
                provider,
                model,
                promptVersion,
                Boolean.toString(imageInputEnabled),
                contextDigest));
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
        return forContext(
                ownerUserId,
                "SUMMARY",
                conversationId,
                fromSeq,
                toSeq,
                provider,
                model,
                promptVersion,
                imageInputEnabled,
                contextDigest);
    }
}
