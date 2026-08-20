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
        return created(message, null);
    }

    static WireEnvelope created(MessageRecord message, Long syncSeq) {
        return new WireEnvelope(
                1,
                "message.created",
                null,
                body(message, syncSeq));
    }

    private static MessageBody body(MessageRecord message) {
        return body(message, null);
    }

    private static MessageBody body(MessageRecord message, Long syncSeq) {
        return new MessageBody(
                message.messageId(),
                message.conversationId(),
                message.senderId(),
                message.clientMsgId(),
                message.conversationSeq(),
                message.type(),
                message.state(),
                message.text(),
                message.mediaId(),
                message.serverAcceptedAt(),
                syncSeq);
    }

    static WireEnvelope error(UUID requestId, ApiErrorDefinition definition) {
        return new WireEnvelope(
                1,
                "error",
                requestId,
                new ErrorBody(definition.code(), definition.message()));
    }

    static WireEnvelope syncReady(UUID deviceId, String deviceClass, long highWatermark) {
        return new WireEnvelope(
                1,
                "sync.ready",
                null,
                new SyncReadyBody(deviceId, deviceClass, highWatermark));
    }

    static WireEnvelope conversationRead(ConversationReadState state, Long syncSeq) {
        return new WireEnvelope(
                1,
                "conversation.read",
                null,
                new ConversationReadBody(
                        state.conversationId(),
                        state.userId(),
                        state.readSeq(),
                        syncSeq));
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
            UUID mediaId,
            java.time.Instant serverAcceptedAt,
            Long syncSeq
    ) {
    }

    record ErrorBody(
            String code,
            String message
    ) {
    }

    record SyncReadyBody(
            UUID deviceId,
            String deviceClass,
            long highWatermark
    ) {
    }

    record ConversationReadBody(
            UUID conversationId,
            UUID userId,
            long readSeq,
            Long syncSeq
    ) {
    }
}
