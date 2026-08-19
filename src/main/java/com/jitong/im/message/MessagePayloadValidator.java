package com.jitong.im.message;

import com.jitong.im.platform.error.ApiErrorDefinition;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class MessagePayloadValidator {

    static final int MAX_TEXT_CODE_POINTS = 4_000;
    static final int MAX_TEXT_BYTES = 16 * 1024;
    static final int MAX_FRAME_BYTES = 64 * 1024;

    private MessagePayloadValidator() {
    }

    static void validateText(String text) {
        if (text == null) {
            throw new MessageException(ApiErrorDefinition.TEXT_TOO_LONG);
        }
        if (text.isBlank()) {
            throw new MessageException(ApiErrorDefinition.INVALID_REQUEST);
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new MessageException(ApiErrorDefinition.TEXT_TOO_LARGE);
        }
        if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) {
            throw new MessageException(ApiErrorDefinition.TEXT_TOO_LONG);
        }
    }

    static void validateFrame(String frame) {
        if (frame == null || frame.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
            throw new MessageException(ApiErrorDefinition.FRAME_TOO_LARGE);
        }
    }

    static void validateClientMessageId(UUID clientMsgId) {
        if (clientMsgId == null || clientMsgId.version() != 4) {
            throw new MessageException(ApiErrorDefinition.INVALID_REQUEST);
        }
    }
}
