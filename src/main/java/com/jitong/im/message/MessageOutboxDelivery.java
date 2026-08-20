package com.jitong.im.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.sync.OutboxDelivery;
import com.jitong.im.sync.OutboxRecord;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
class MessageOutboxDelivery implements OutboxDelivery {

    private final ObjectMapper objectMapper;
    private final MessageRepository messageRepository;
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessionsByDevice = new ConcurrentHashMap<>();

    MessageOutboxDelivery(ObjectMapper objectMapper, MessageRepository messageRepository) {
        this.objectMapper = objectMapper;
        this.messageRepository = messageRepository;
    }

    void register(UUID deviceId, WebSocketSession session) {
        sessionsByDevice.computeIfAbsent(deviceId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    void unregister(UUID deviceId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByDevice.get(deviceId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByDevice.remove(deviceId, sessions);
        }
    }

    @Override
    public boolean deliver(OutboxRecord record) {
        if (messageRepository.findUserIdForDevice(record.targetDeviceId()) == null) {
            return false;
        }
        Set<WebSocketSession> sessions = sessionsByDevice.get(record.targetDeviceId());
        if (sessions == null || sessions.isEmpty()) {
            return false;
        }
        MessageRecord message = messageRepository.findById(record.entityId());
        boolean delivered = false;
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                        MessageWire.created(message, record.syncSeq()))));
                delivered = true;
            } catch (IOException ignored) {
                // Leave the durable outbox row pending for a later retry.
            }
        }
        return delivered;
    }
}
