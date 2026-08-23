package com.jitong.im.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class AiExtractionValidator {

    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");

    private AiExtractionValidator() {
    }

    static void validate(AiExtraction result, AiSummaryContext context) {
        if (result == null || result.actionItems() == null || result.keyFacts() == null
                || result.actionItems().size() > 100 || result.keyFacts().size() > 100) {
            throw invalid("Information extraction result is invalid");
        }
        Set<UUID> allowedEvidence = new HashSet<>();
        for (AiContextMessage message : context.messages()) {
            allowedEvidence.add(message.messageId());
        }
        for (AiExtraction.ActionItem item : result.actionItems()) {
            if (item == null
                    || blankOrLong(item.title(), 500)
                    || item.details() == null
                    || item.details().length() > 4000
                    || !PRIORITIES.contains(item.priority())
                    || invalidConfidence(item.confidence())) {
                throw invalid("Extracted action item is invalid");
            }
            validateEvidence(item.sourceMessageIds(), allowedEvidence);
        }
        for (AiExtraction.KeyFact fact : result.keyFacts()) {
            if (fact == null
                    || blankOrLong(fact.category(), 80)
                    || blankOrLong(fact.content(), 4000)
                    || invalidConfidence(fact.confidence())) {
                throw invalid("Extracted key fact is invalid");
            }
            validateEvidence(fact.sourceMessageIds(), allowedEvidence);
        }
    }

    private static void validateEvidence(List<UUID> evidence, Set<UUID> allowedEvidence) {
        if (evidence == null || evidence.isEmpty()
                || evidence.stream().anyMatch(id -> id == null || !allowedEvidence.contains(id))) {
            throw invalid("Extraction evidence is outside the authorized context");
        }
    }

    private static boolean blankOrLong(String value, int maxLength) {
        return value == null || value.isBlank() || value.length() > maxLength;
    }

    private static boolean invalidConfidence(double confidence) {
        return !Double.isFinite(confidence) || confidence < 0 || confidence > 1;
    }

    private static AiProviderException invalid(String message) {
        return new AiProviderException("AI_INVALID_RESULT", message);
    }
}
