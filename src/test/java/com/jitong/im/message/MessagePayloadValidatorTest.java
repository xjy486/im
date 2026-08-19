package com.jitong.im.message;

import com.jitong.im.platform.error.ApiErrorDefinition;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagePayloadValidatorTest {

    @Test
    void accepts_text_within_code_point_and_utf8_limits() {
        MessagePayloadValidator.validateText("a".repeat(4000));
        MessagePayloadValidator.validateText("中".repeat(4000));
    }

    @Test
    void rejects_text_above_code_point_limit() {
        assertThatThrownBy(() -> MessagePayloadValidator.validateText("a".repeat(4001)))
                .isInstanceOf(MessageException.class)
                .extracting(exception -> ((MessageException) exception).definition())
                .isEqualTo(ApiErrorDefinition.TEXT_TOO_LONG);
    }

    @Test
    void rejects_business_frames_above_64_kibibytes() {
        assertThatThrownBy(() -> MessagePayloadValidator.validateFrame("x".repeat(64 * 1024 + 1)))
                .isInstanceOf(MessageException.class)
                .extracting(exception -> ((MessageException) exception).definition())
                .isEqualTo(ApiErrorDefinition.FRAME_TOO_LARGE);
    }

    @Test
    void rejects_non_v4_client_message_ids() {
        assertThatThrownBy(() -> MessagePayloadValidator.validateClientMessageId(
                UUID.fromString("00000000-0000-3000-8000-000000000000")))
                .isInstanceOf(MessageException.class)
                .extracting(exception -> ((MessageException) exception).definition())
                .isEqualTo(ApiErrorDefinition.INVALID_REQUEST);
    }
}
