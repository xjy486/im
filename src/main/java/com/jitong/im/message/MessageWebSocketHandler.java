package com.jitong.im.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.auth.AuthService;
import com.jitong.im.auth.AuthenticatedDevice;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
class MessageWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final AuthService authService;
    private final MessageService messageService;
    private final SyncService syncService;
    private final MessageOutboxDelivery outboxDelivery;
    private final ConcurrentHashMap<String, UUID> devicesBySession = new ConcurrentHashMap<>();

    MessageWebSocketHandler(
            ObjectMapper objectMapper,
            AuthService authService,
            MessageService messageService,
            SyncService syncService,
            MessageOutboxDelivery outboxDelivery
    ) {
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.messageService = messageService;
        this.syncService = syncService;
        this.outboxDelivery = outboxDelivery;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String authorization = (String) session.getAttributes()
                .get(MessageWebSocketHandshakeInterceptor.AUTHORIZATION_ATTRIBUTE);
        AuthenticatedDevice device;
        try {
            device = authService.requireAuthenticatedDevice(authorization);
            send(session, MessageWire.syncReady(
                    device.deviceId(),
                    device.deviceClass(),
                    syncService.highWatermark(device.userId())));
            outboxDelivery.register(device.deviceId(), session);
        } catch (RuntimeException exception) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        devicesBySession.put(session.getId(), device.deviceId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        UUID deviceId = devicesBySession.get(session.getId());
        if (deviceId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String payload = message.getPayload();
        UUID requestId = null;
        try {
            AuthenticatedDevice device = authService.requireAuthenticatedDevice((String) session.getAttributes()
                    .get(MessageWebSocketHandshakeInterceptor.AUTHORIZATION_ATTRIBUTE));
            if (!device.deviceId().equals(deviceId)) {
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            MessagePayloadValidator.validateFrame(payload);
            JsonNode envelope = objectMapper.readTree(payload);
            validateEnvelope(envelope);
            requestId = UUID.fromString(envelope.get("requestId").asText());
            JsonNode body = envelope.get("body");
            MessageSendResult result = messageService.sendText(
                    device.userId(),
                    UUID.fromString(body.get("conversationId").asText()),
                    UUID.fromString(body.get("clientMsgId").asText()),
                    body.get("text").asText());
            send(session, MessageWire.ack(requestId, result.message()));
        } catch (MessageException exception) {
            sendError(session, requestId, exception.definition());
        } catch (Exception exception) {
            sendError(session, requestId, ApiErrorDefinition.INVALID_REQUEST);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID deviceId = devicesBySession.remove(session.getId());
        if (deviceId == null) {
            return;
        }
        outboxDelivery.unregister(deviceId, session);
    }

    private void validateEnvelope(JsonNode envelope) {
        if (envelope == null
                || envelope.get("version") == null
                || envelope.get("version").asInt() != 1
                || envelope.get("operation") == null
                || !"message.send".equals(envelope.get("operation").asText())
                || envelope.get("requestId") == null
                || envelope.get("body") == null
                || envelope.get("body").get("conversationId") == null
                || envelope.get("body").get("clientMsgId") == null
                || envelope.get("body").get("text") == null) {
            throw new MessageException(ApiErrorDefinition.INVALID_REQUEST);
        }
    }

    private void sendError(WebSocketSession session, UUID requestId, ApiErrorDefinition definition) {
        try {
            send(session, MessageWire.error(requestId, definition));
        } catch (IOException ignored) {
            // The command result is durable; the next synchronization repairs the response.
        }
    }

    private void send(WebSocketSession session, MessageWire.WireEnvelope envelope) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }

}
