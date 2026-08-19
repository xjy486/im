package com.jitong.im.message;

import com.jitong.im.platform.error.ApiErrorDefinition;

import java.util.UUID;

final class MessageWire {

    private MessageWire() {
    }

    static WireEnvelope ack(UUID requestId, MessageRecord message) {
        return new WireEnvelope(
                1,
                "message.ack",
                requestId,
                body(message));
    }

    static WireEnvelope created(MessageRecord message) {
        return new WireEnvelope(
                1,
                "message.created",
                null,
                body(message));
    }

    private static MessageBody body(MessageRecord message) {
        return new MessageBody(
                message.messageId(),
                message.conversationId(),
                message.senderId(),
                message.clientMsgId(),
                message.conversationSeq(),
                message.type(),
                message.state(),
                message.text(),
                message.serverAcceptedAt());
    }

    static WireEnvelope error(UUID requestId, ApiErrorDefinition definition) {
        return new WireEnvelope(
                1,
                "error",
                requestId,
                new ErrorBody(definition.code(), definition.message()));
    }

    record WireEnvelope(
            int version,
            String operation,
            UUID requestId,
            Object body
    ) {
    }

    record MessageBody(
            UUID messageId,
            UUID conversationId,
            UUID senderId,
            UUID clientMsgId,
            long conversationSeq,
            String type,
            String state,
            String text,
            java.time.Instant serverAcceptedAt
    ) {
    }

    record ErrorBody(
            String code,
            String message
    ) {
    }
}
