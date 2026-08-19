package com.jitong.im.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.auth.AuthService;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
class MessageWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final AuthService authService;
    private final MessageService messageService;
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> usersBySession = new ConcurrentHashMap<>();

    MessageWebSocketHandler(
            ObjectMapper objectMapper,
            AuthService authService,
            MessageService messageService
    ) {
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.messageService = messageService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String authorization = (String) session.getAttributes()
                .get(MessageWebSocketHandshakeInterceptor.AUTHORIZATION_ATTRIBUTE);
        UUID userId;
        try {
            userId = authService.requireUserId(authorization);
        } catch (RuntimeException exception) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        usersBySession.put(session.getId(), userId);
        sessionsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        UUID userId = usersBySession.get(session.getId());
        if (userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String payload = message.getPayload();
        UUID requestId = null;
        try {
            MessagePayloadValidator.validateFrame(payload);
            JsonNode envelope = objectMapper.readTree(payload);
            validateEnvelope(envelope);
            requestId = UUID.fromString(envelope.get("requestId").asText());
            JsonNode body = envelope.get("body");
            MessageSendResult result = messageService.sendText(
                    userId,
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
        UUID userId = usersBySession.remove(session.getId());
        if (userId == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByUser.remove(userId, sessions);
            }
        }
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onMessageAccepted(MessageAcceptedEvent event) {
        broadcast(event.message());
    }

    private void broadcast(MessageRecord message) {
        Set<WebSocketSession> senderSessions = sessionsByUser.get(message.senderId());
        if (senderSessions != null) {
            senderSessions.forEach(session -> sendQuietly(session, MessageWire.created(message)));
        }
        MessageRepository.ConversationTarget target = messageServiceTarget(message);
        if (target != null) {
            Set<WebSocketSession> recipients = sessionsByUser.get(target.peerUserId());
            if (recipients != null) {
                recipients.forEach(session -> sendQuietly(session, MessageWire.created(message)));
            }
        }
    }

    private MessageRepository.ConversationTarget messageServiceTarget(MessageRecord message) {
        // The message service deliberately owns authorization and persistence. The
        // receiver is resolved by the WebSocket registry through the conversation.
        // A lightweight lookup is exposed by the service for post-commit delivery.
        return messageService.target(message.conversationId(), message.senderId());
    }

    private void sendError(WebSocketSession session, UUID requestId, ApiErrorDefinition definition) {
        sendQuietly(session, MessageWire.error(requestId, definition));
    }

    private void send(WebSocketSession session, MessageWire.WireEnvelope envelope) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }

    private void sendQuietly(WebSocketSession session, MessageWire.WireEnvelope envelope) {
        if (!session.isOpen()) {
            return;
        }
        try {
            send(session, envelope);
        } catch (IOException ignored) {
            // The durable message is already committed. The next sync ticket repairs delivery.
        }
    }
}
