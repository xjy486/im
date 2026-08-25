package com.jitong.im.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.auth.AuthService;
import com.jitong.im.auth.AuthenticatedDevice;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.media.MediaException;
import com.jitong.im.sync.SyncService;
import com.jitong.im.platform.observability.OperationalMetrics;
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
    private final OperationalMetrics metrics;
    private final ConcurrentHashMap<String, UUID> devicesBySession = new ConcurrentHashMap<>();

    MessageWebSocketHandler(
            ObjectMapper objectMapper,
            AuthService authService,
            MessageService messageService,
            SyncService syncService,
            MessageOutboxDelivery outboxDelivery,
            OperationalMetrics metrics
    ) {
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.messageService = messageService;
        this.syncService = syncService;
        this.outboxDelivery = outboxDelivery;
        this.metrics = metrics;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String authorization = (String) session.getAttributes()
                .get(MessageWebSocketHandshakeInterceptor.AUTHORIZATION_ATTRIBUTE);
        AuthenticatedDevice device = null;
        try {
            device = authService.requireAuthenticatedDevice(authorization);
            outboxDelivery.register(device.deviceId(), session);
            metrics.activeWebSocketConnections().incrementAndGet();
            send(session, MessageWire.syncReady(
                    device.deviceId(),
                    device.deviceClass(),
                    syncService.highWatermark(device.userId())));
        } catch (RuntimeException exception) {
            if (device != null) {
                outboxDelivery.unregister(device.deviceId(), session);
                metrics.activeWebSocketConnections().decrementAndGet();
            }
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
            UUID conversationId = UUID.fromString(body.get("conversationId").asText());
            UUID clientMessageId = UUID.fromString(body.get("clientMsgId").asText());
            MessageSendResult result;
            long startedAt = System.nanoTime();
            String type = body.path("type").asText("TEXT");
            if ("IMAGE".equals(type)) {
                result = messageService.sendImage(
                        device.userId(),
                        conversationId,
                        clientMessageId,
                        UUID.fromString(body.get("mediaId").asText()));
            } else if ("TEXT".equals(type)) {
                result = messageService.sendText(
                        device.userId(),
                        conversationId,
                        clientMessageId,
                        body.get("text").asText());
            } else {
                throw new MessageException(ApiErrorDefinition.INVALID_REQUEST);
            }
            metrics.messageAckLatency().record(
                    System.nanoTime() - startedAt,
                    java.util.concurrent.TimeUnit.NANOSECONDS);
            send(session, MessageWire.ack(requestId, result.message()));
        } catch (MessageException exception) {
            sendError(session, requestId, exception.definition());
        } catch (MediaException exception) {
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
        metrics.activeWebSocketConnections().decrementAndGet();
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
                || (!"TEXT".equals(envelope.get("body").path("type").asText("TEXT"))
                    && !"IMAGE".equals(envelope.get("body").path("type").asText("TEXT")))
                || ("IMAGE".equals(envelope.get("body").path("type").asText("TEXT"))
                    ? envelope.get("body").get("mediaId") == null
                    : envelope.get("body").get("text") == null)) {
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
        outboxDelivery.send(session, envelope);
    }

}
