package com.jitong.im.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.sync.OutboxDelivery;
import com.jitong.im.sync.OutboxRecord;
import com.jitong.im.push.FcmSender;
import com.jitong.im.push.FcmDeliveryResult;
import com.jitong.im.auth.DevicePushTokenService;
import com.jitong.im.media.AvatarService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ReadStateRepository readStateRepository;
    private final DevicePushTokenService pushTokenService;
    private final FcmSender fcmSender;
    private final AvatarService avatarService;
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessionsByDevice = new ConcurrentHashMap<>();
    private final Object sessionsLock = new Object();

    @Autowired
    MessageOutboxDelivery(
            ObjectMapper objectMapper,
            MessageRepository messageRepository,
            ReadStateRepository readStateRepository,
            DevicePushTokenService pushTokenService,
            FcmSender fcmSender,
            AvatarService avatarService
    ) {
        this.objectMapper = objectMapper;
        this.messageRepository = messageRepository;
        this.readStateRepository = readStateRepository;
        this.pushTokenService = pushTokenService;
        this.fcmSender = fcmSender;
        this.avatarService = avatarService;
    }

    MessageOutboxDelivery(
            ObjectMapper objectMapper,
            MessageRepository messageRepository,
            ReadStateRepository readStateRepository,
            DevicePushTokenService pushTokenService,
            FcmSender fcmSender
    ) {
        this(
                objectMapper,
                messageRepository,
                readStateRepository,
                pushTokenService,
                fcmSender,
                null);
    }

    void register(UUID deviceId, WebSocketSession session) {
        synchronized (sessionsLock) {
            sessionsByDevice.computeIfAbsent(deviceId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        }
    }

    void unregister(UUID deviceId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByDevice.get(deviceId);
        if (sessions == null) {
            return;
        }
        synchronized (sessionsLock) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByDevice.remove(deviceId, sessions);
            }
        }
    }

    @Override
    public boolean deliver(OutboxRecord record) {
        if (messageRepository.findUserIdForDevice(record.targetDeviceId()) == null) {
            // A revoked or replaced device no longer has a delivery obligation. Its
            // durable sync state is no longer readable by that device either.
            return true;
        }
        if (record.conversationId() != null
                && messageRepository.isGroupConversation(record.conversationId())
                && !"MEMBERSHIP_REVOKED".equals(record.eventType())
                && !"MEMBERSHIP_GRANTED".equals(record.eventType())
                && !messageRepository.canDeviceReceiveGroupEvent(
                        record.targetDeviceId(),
                        record.conversationId(),
                        record.syncSeq())) {
            // A membership change can revoke a device's right to receive
            // already-created group events while those outbox rows are still
            // pending. The revocation marker itself remains deliverable so the
            // client can erase its local group copy.
            return true;
        }
        if ("MESSAGE_CREATED".equals(record.eventType())) {
            MessageRecord current = messageRepository.findById(record.entityId());
            if (current != null
                    && ("RECALLED".equals(current.state()) || "MODERATED".equals(current.state()))) {
                return true;
            }
        }
        Set<WebSocketSession> sessions = sessionsByDevice.get(record.targetDeviceId());
        if (sessions == null || sessions.isEmpty()) {
            if (!pushTokenService.isMobile(record.targetDeviceId())) {
                return true;
            }
            return deliverViaFcm(record);
        }
        MessageWire.WireEnvelope envelope = switch (record.eventType()) {
            case "MESSAGE_CREATED" -> MessageWire.created(
                    messageRepository.findById(record.entityId()),
                    record.syncSeq());
            case "MESSAGE_RECALLED" -> MessageWire.recalled(
                    messageRepository.findById(record.entityId()),
                    record.syncSeq());
            case "MESSAGE_MODERATED" -> MessageWire.moderated(
                    messageRepository.findById(record.entityId()),
                    record.syncSeq());
            case "CONVERSATION_READ" -> MessageWire.conversationRead(
                    readStateRepository.findState(record.conversationId(), record.entityId()),
                    record.syncSeq());
            case "USER_PROFILE_UPDATED" -> {
                if (avatarService == null) {
                    yield null;
                }
                AvatarService.UserProfile profile = avatarService.profile(record.entityId());
                yield profile == null
                        ? null
                        : MessageWire.userProfileUpdated(
                                profile.userId(),
                                profile.displayName(),
                                profile.avatarUrl(),
                                profile.avatarVersion(),
                                profile.avatarFallback(),
                                record.syncSeq());
            }
            case "GROUP_PROFILE_UPDATED" -> {
                if (avatarService == null) {
                    yield null;
                }
                AvatarService.GroupProfile profile =
                        avatarService.groupProfile(record.conversationId());
                yield profile == null
                        ? null
                        : MessageWire.groupProfileUpdated(
                                profile.conversationId(),
                                profile.avatarUrl(),
                                profile.avatarVersion(),
                                record.syncSeq());
            }
            case "MEMBERSHIP_REVOKED" ->
                    MessageWire.membershipRevoked(record.conversationId(), record.syncSeq());
            case "MEMBERSHIP_GRANTED" ->
                    MessageWire.membershipGranted(record.conversationId(), record.syncSeq());
            default -> null;
        };
        if (envelope == null) {
            return false;
        }
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (IOException ignored) {
            return false;
        }
        boolean delivered = false;
        synchronized (sessionsLock) {
            sessions.removeIf(session -> !session.isOpen());
            if (!sessions.isEmpty()) {
                for (WebSocketSession session : sessions) {
                    try {
                        session.sendMessage(new TextMessage(payload));
                        delivered = true;
                    } catch (IOException ignored) {
                        sessions.remove(session);
                        // Leave the durable outbox row pending for a later retry if FCM
                        // cannot provide a prompt either.
                    }
                }
            }
            if (sessions.isEmpty()) {
                sessionsByDevice.remove(record.targetDeviceId(), sessions);
            }
        }
        if (delivered) {
            return delivered;
        }
        return deliverViaFcm(record);
    }

    private boolean deliverViaFcm(OutboxRecord record) {
        if (!pushTokenService.isMobile(record.targetDeviceId())) {
            return true;
        }
        String token = pushTokenService.find(record.targetDeviceId());
        FcmDeliveryResult result = switch (record.eventType()) {
            case "MESSAGE_CREATED", "MESSAGE_RECALLED", "MESSAGE_MODERATED" ->
                    fcmSender.sendNewMessage(token);
            case "USER_PROFILE_UPDATED", "GROUP_PROFILE_UPDATED" ->
                    fcmSender.sendProfileChanged(token);
            default -> FcmDeliveryResult.SENT;
        };
        if (result == FcmDeliveryResult.PERMANENT_TOKEN_FAILURE) {
            if (token != null) {
                pushTokenService.clearIfCurrent(record.targetDeviceId(), token);
            }
        }
        return result != FcmDeliveryResult.RETRYABLE_FAILURE;
    }
}
