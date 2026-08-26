package com.jitong.im.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.sync.OutboxDelivery;
import com.jitong.im.sync.OutboxRecord;
import com.jitong.im.push.FcmSender;
import com.jitong.im.push.FcmDeliveryResult;
import com.jitong.im.auth.DevicePushTokenService;
import com.jitong.im.auth.AuthCredentialsRevokedEvent;
import com.jitong.im.ai.AiDelivery;
import com.jitong.im.ai.AiService;
import com.jitong.im.media.AvatarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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
    private final AiService aiService;
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessionsByDevice = new ConcurrentHashMap<>();
    private final Object sessionsLock = new Object();

    @Autowired
    MessageOutboxDelivery(
            ObjectMapper objectMapper,
            MessageRepository messageRepository,
            ReadStateRepository readStateRepository,
            DevicePushTokenService pushTokenService,
            FcmSender fcmSender,
            AvatarService avatarService,
            AiService aiService
    ) {
        this.objectMapper = objectMapper;
        this.messageRepository = messageRepository;
        this.readStateRepository = readStateRepository;
        this.pushTokenService = pushTokenService;
        this.fcmSender = fcmSender;
        this.avatarService = avatarService;
        this.aiService = aiService;
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
                null,
                null);
    }

    MessageOutboxDelivery(
            ObjectMapper objectMapper,
            MessageRepository messageRepository,
            ReadStateRepository readStateRepository,
            DevicePushTokenService pushTokenService,
            FcmSender fcmSender,
            AiService aiService
    ) {
        this(
                objectMapper,
                messageRepository,
                readStateRepository,
                pushTokenService,
                fcmSender,
                null,
                aiService);
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

    void closeDevices(Set<UUID> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return;
        }
        synchronized (sessionsLock) {
            for (UUID deviceId : deviceIds) {
                Set<WebSocketSession> sessions = sessionsByDevice.remove(deviceId);
                if (sessions == null) {
                    continue;
                }
                for (WebSocketSession session : sessions) {
                    try {
                        session.close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
                    } catch (IOException ignored) {
                        // The session is no longer usable; removal above is sufficient.
                    }
                }
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void closeRevokedDevices(AuthCredentialsRevokedEvent event) {
        closeDevices(event.deviceIds());
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
                && !"GROUP_DISSOLVED".equals(record.eventType())
                && !"MEMBERSHIP_REVOKED".equals(record.eventType())
                && !"MEMBERSHIP_GRANTED".equals(record.eventType())
                && !"GROUP_INVITE".equals(record.eventType())
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
            case "GROUP_DISSOLVED" ->
                    MessageWire.groupDissolved(record.conversationId(), record.syncSeq());
            case "CONTACT_RELATIONSHIP_CHANGED" ->
                    MessageWire.contactRelationshipChanged(record.conversationId(), record.syncSeq());
            case "CONTACT_REQUEST_CREATED" ->
                    MessageWire.contactRequestCreated(record.entityId(), record.syncSeq());
            case "GROUP_INVITE" -> MessageWire.groupInviteCreated(
                    record.entityId(),
                    record.conversationId(),
                    record.syncSeq());
            case "CONVERSATION_AI_POLICY_CHANGED" ->
                    MessageWire.conversationAiPolicyChanged(
                            record.conversationId(),
                            record.syncSeq());
            case "AI_JOB_QUEUED", "AI_JOB_STARTED", "AI_JOB_COMPLETED", "AI_JOB_FAILED" -> {
                if (aiService == null) {
                    yield null;
                }
                AiDelivery job = aiService.deliveryForSync(
                        messageRepository.findUserIdForDevice(record.targetDeviceId()),
                        record.entityId());
                if (job == null) {
                    yield MessageWire.error(
                            null,
                            com.jitong.im.platform.error.ApiErrorDefinition.AI_NOT_FOUND);
                }
                yield MessageWire.aiJob(job, record.syncSeq());
            }
            case "AI_ARTIFACT_DELETED" -> MessageWire.aiArtifactDeleted(
                    record.entityId(),
                    record.conversationId(),
                    record.syncSeq());
            case "AI_JOB_DELETED" -> MessageWire.aiJobDeleted(
                    record.entityId(),
                    record.conversationId(),
                    record.syncSeq());
            case "AI_ACTION_ITEM_UPDATED" -> MessageWire.aiActionItemChanged(
                    "ai.action-item.updated",
                    record.entityId(),
                    record.conversationId(),
                    record.syncSeq());
            case "AI_ACTION_ITEM_DELETED" -> MessageWire.aiActionItemChanged(
                    "ai.action-item.deleted",
                    record.entityId(),
                    record.conversationId(),
                    record.syncSeq());
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
                        sendPayload(session, payload);
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

    void send(WebSocketSession session, MessageWire.WireEnvelope envelope) throws IOException {
        String payload = objectMapper.writeValueAsString(envelope);
        synchronized (sessionsLock) {
            sendPayload(session, payload);
        }
    }

    private void sendPayload(WebSocketSession session, String payload) throws IOException {
        session.sendMessage(new TextMessage(payload));
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
            case "GROUP_DISSOLVED" ->
                    fcmSender.sendProfileChanged(token);
            case "CONTACT_RELATIONSHIP_CHANGED" ->
                    fcmSender.sendProfileChanged(token);
            case "CONVERSATION_AI_POLICY_CHANGED" ->
                    fcmSender.sendProfileChanged(token);
            case "CONTACT_REQUEST_CREATED" ->
                    fcmSender.sendContactRequest(token);
            case "GROUP_INVITE" -> fcmSender.sendGroupInvite(token);
            default -> FcmDeliveryResult.SENT;
        };
        if (result == null) {
            return false;
        }
        if (result == FcmDeliveryResult.PERMANENT_TOKEN_FAILURE) {
            if (token != null) {
                pushTokenService.clearIfCurrent(record.targetDeviceId(), token);
            }
        }
        return switch (result) {
            case SENT, PERMANENT_TOKEN_FAILURE -> true;
            case NOT_CONFIGURED, NO_TOKEN, RETRYABLE_FAILURE -> false;
        };
    }
}
