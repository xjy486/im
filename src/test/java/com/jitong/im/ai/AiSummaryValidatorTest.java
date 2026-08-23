package com.jitong.im.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSummaryValidatorTest {

    @Test
    void rejects_evidence_message_ids_outside_the_authorized_context() {
        UUID authorized = UUID.randomUUID();
        UUID unauthorized = UUID.randomUUID();
        AiSummaryContext context = new AiSummaryContext(
                UUID.randomUUID(),
                List.of(new AiContextMessage(authorized, 1, UUID.randomUUID(), "hello")));

        assertThatThrownBy(() -> AiSummaryValidator.validate(
                new AiSummary(
                        "overview",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(unauthorized)),
                context))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("outside the authorized context");
    }
}
