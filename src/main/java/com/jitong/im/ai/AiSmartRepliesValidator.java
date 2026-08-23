package com.jitong.im.ai;

final class AiSmartRepliesValidator {

    static final int REQUIRED_REPLY_COUNT = 3;

    private AiSmartRepliesValidator() {
    }

    static void validate(AiSmartReplies result) {
        if (result == null || result.replies() == null
                || result.replies().size() != REQUIRED_REPLY_COUNT) {
            throw new AiProviderException(
                    "AI_INVALID_RESULT",
                    "Smart reply result must contain exactly three drafts");
        }
        for (AiSmartReplies.Draft draft : result.replies()) {
            if (draft == null
                    || draft.text() == null
                    || draft.text().isBlank()
                    || draft.text().length() > 2000
                    || draft.tone() == null
                    || draft.tone().isBlank()
                    || draft.tone().length() > 40) {
                throw new AiProviderException("AI_INVALID_RESULT", "Smart reply draft is invalid");
            }
        }
    }
}
