package com.jitong.im.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiExtraction(
        List<ActionItem> actionItems,
        List<KeyFact> keyFacts
) {
    public AiExtraction {
        actionItems = List.copyOf(actionItems);
        keyFacts = List.copyOf(keyFacts);
    }

    public record ActionItem(
            String title,
            String details,
            UUID assigneeUserId,
            Instant dueAt,
            String priority,
            double confidence,
            List<UUID> sourceMessageIds
    ) {
        public ActionItem {
            sourceMessageIds = List.copyOf(sourceMessageIds);
        }
    }

    public record KeyFact(
            String category,
            String content,
            double confidence,
            List<UUID> sourceMessageIds
    ) {
        public KeyFact {
            sourceMessageIds = List.copyOf(sourceMessageIds);
        }
    }
}
