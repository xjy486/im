package com.jitong.im.ai;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class AiSummaryValidator {

    private AiSummaryValidator() {
    }

    static void validate(AiSummary summary, AiSummaryContext context) {
        if (summary == null || summary.overview() == null || summary.overview().isBlank()
                || summary.overview().length() > 4000) {
            throw new AiProviderException("AI_INVALID_RESULT", "Summary overview is invalid");
        }
        if (summary.keyPoints().size() > 20
                || summary.decisions().size() > 20
                || summary.openQuestions().size() > 20) {
            throw new AiProviderException("AI_INVALID_RESULT", "Summary contains too many items");
        }
        Set<UUID> allowed = new HashSet<>();
        for (AiContextMessage message : context.messages()) {
            allowed.add(message.messageId());
        }
        for (UUID sourceMessageId : summary.sourceMessageIds()) {
            if (!allowed.contains(sourceMessageId)) {
                throw new AiProviderException(
                        "AI_INVALID_RESULT",
                        "Summary evidence is outside the authorized context");
            }
        }
    }
}
