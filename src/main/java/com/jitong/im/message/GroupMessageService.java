package com.jitong.im.message;

import com.jitong.im.auth.UuidV7;
import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.AuditSubjectType;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import com.jitong.im.media.MediaService;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Owns the group-specific part of the message transaction.
 *
 * Group membership changes call this service after changing membership state,
 * so the membership boundary and the lifecycle message are committed in the
 * same transaction as the change that caused them.
 */
@Service
public class GroupMessageService {

    private final MessageRepository repository;
    private final SyncService syncService;
    private final MediaService mediaService;
    private final SecurityAuditSink auditSink;
    private final Clock clock;

    @Autowired
    GroupMessageService(
            MessageRepository repository,
            SyncService syncService,
            MediaService mediaService,
            SecurityAuditSink auditSink
    ) {
        this(repository, syncService, mediaService, auditSink, Clock.systemUTC());
    }

    GroupMessageService(
            MessageRepository repository,
            SyncService syncService,
            MediaService mediaService,
            SecurityAuditSink auditSink,
            Clock clock
    ) {
        this.repository = repository;
        this.syncService = syncService;
        this.mediaService = mediaService;
        this.auditSink = auditSink;
        this.clock = clock;
    }

    MessageSendResult sendText(
            UUID senderId,
            UUID conversationId,
            UUID clientMsgId,
            String text
    ) {
        MessagePayloadValidator.validateText(text);
        MessagePayloadValidator.validateClientMessageId(clientMsgId);
        MessageRepository.GroupConversationTarget target =
                repository.lockGroupConversation(conversationId, senderId);
        if (target == null) {
            throw new MessageException(ApiErrorDefinition.NOT_MEMBER);
        }

        MessageRecord previous = repository.findByClientMessageId(senderId, clientMsgId);
        if (previous != null) {
            if (!previous.conversationId().equals(conversationId)
                    || !"TEXT".equals(previous.type())
                    || !text.equals(previous.text())) {
                throw new MessageException(ApiErrorDefinition.IDEMPOTENCY_CONFLICT);
            }
            return new MessageSendResult(previous, false);
        }

        long sequence = repository.nextConversationSequence(conversationId);
        MessageRecord message = repository.insertTextMessage(
                UuidV7.random(),
                conversationId,
                sequence,
                senderId,
                clientMsgId,
                text,
                clock.instant());
        recordMessageCreated(message);
        return new MessageSendResult(message, true);
    }

    MessageSendResult sendImage(
            UUID senderId,
            UUID conversationId,
            UUID clientMsgId,
            UUID mediaId
    ) {
        MessagePayloadValidator.validateClientMessageId(clientMsgId);
        if (mediaId == null) {
            throw new MessageException(ApiErrorDefinition.MEDIA_INVALID);
        }
        MessageRepository.GroupConversationTarget target =
                repository.lockGroupConversation(conversationId, senderId);
        if (target == null) {
            throw new MessageException(ApiErrorDefinition.NOT_MEMBER);
        }

        MessageRecord previous = repository.findByClientMessageId(senderId, clientMsgId);
        if (previous != null) {
            if (!previous.conversationId().equals(conversationId)
                    || !"IMAGE".equals(previous.type())
                    || !mediaId.equals(previous.mediaId())) {
                throw new MessageException(ApiErrorDefinition.IDEMPOTENCY_CONFLICT);
            }
            return new MessageSendResult(previous, false);
        }

        UUID messageId = UuidV7.random();
        mediaService.bindMessageImage(senderId, mediaId, messageId);
        long sequence = repository.nextConversationSequence(conversationId);
        MessageRecord message = repository.insertImageMessage(
                messageId,
                conversationId,
                sequence,
                senderId,
                clientMsgId,
                mediaId,
                clock.instant());
        recordMessageCreated(message);
        return new MessageSendResult(message, true);
    }

    public void recordGroupCreated(UUID conversationId, UUID ownerUserId) {
        recordSystemMessage(conversationId, ownerUserId, "GROUP_CREATED", null, null);
    }

    public void recordMemberJoinedAfterMembershipChange(
            UUID conversationId,
            UUID actorId,
            UUID joinedUserId
    ) {
        recordSystemMessage(conversationId, actorId, "MEMBER_JOINED", joinedUserId, "MEMBER");
        recordMembershipGranted(conversationId, joinedUserId);
    }

    public void recordMemberLeft(UUID conversationId, UUID actorId) {
        recordSystemMessage(conversationId, actorId, "MEMBER_LEFT", actorId, null);
        recordMembershipRevoked(conversationId, actorId);
    }

    public void recordMemberRemoved(UUID conversationId, UUID actorId, UUID removedUserId) {
        recordSystemMessage(conversationId, actorId, "MEMBER_REMOVED", removedUserId, null);
        recordMembershipRevoked(conversationId, removedUserId);
    }

    public void recordRoleChanged(
            UUID conversationId,
            UUID actorId,
            UUID targetUserId,
            String role
    ) {
        recordSystemMessage(conversationId, actorId, "ROLE_CHANGED", targetUserId, role);
    }

    public void recordOwnerTransferred(
            UUID conversationId,
            UUID actorId,
            UUID targetUserId
    ) {
        recordSystemMessage(conversationId, actorId, "ROLE_CHANGED", actorId, "ADMIN");
        recordSystemMessage(conversationId, actorId, "ROLE_CHANGED", targetUserId, "OWNER");
    }

    public void recordGroupProfileUpdated(UUID conversationId, UUID actorId) {
        recordSystemMessage(conversationId, actorId, "GROUP_PROFILE_UPDATED", null, null);
    }

    ConversationMessagePage listMessages(
            UUID userId,
            UUID conversationId,
            long afterSequence,
            int limit
    ) {
        MessageRepository.GroupConversationTarget target =
                repository.findGroupConversation(conversationId, userId);
        if (target == null) {
            throw new MessageException(ApiErrorDefinition.NOT_MEMBER);
        }
        return new ConversationMessagePage(
                1,
                conversationId,
                List.copyOf(repository.listGroupMessages(
                        conversationId,
                        afterSequence,
                        target.historyVisibleAfterSeq(),
                        limit)));
    }

    MessageRecord recall(UUID senderId, UUID messageId) {
        UUID conversationId = repository.findConversationId(messageId);
        if (conversationId == null
                || repository.lockGroupConversation(conversationId, senderId) == null) {
            throw new MessageException(ApiErrorDefinition.FORBIDDEN);
        }
        MessageRecord message = repository.findByIdForUpdate(messageId);
        if (message == null || !senderId.equals(message.senderId())) {
            throw new MessageException(ApiErrorDefinition.FORBIDDEN);
        }
        if ("SYSTEM".equals(message.type())) {
            throw new MessageException(ApiErrorDefinition.FORBIDDEN);
        }
        if ("RECALLED".equals(message.state())) {
            return message;
        }
        if (!"ACTIVE".equals(message.state())) {
            throw new MessageException(ApiErrorDefinition.FORBIDDEN);
        }
        Instant now = clock.instant();
        if (!now.isBefore(message.serverAcceptedAt().plusSeconds(60))) {
            throw new MessageException(ApiErrorDefinition.RECALL_WINDOW_EXPIRED);
        }
        if (message.mediaId() != null) {
            mediaService.expireBoundMedia(message.messageId());
        }
        repository.recallMessage(messageId, now);
        MessageRecord recalled = repository.findById(messageId);
        for (UUID memberId : repository.groupActiveMemberIds(message.conversationId())
                .stream()
                .sorted()
                .toList()) {
            long syncSeq = syncService.allocateSequence(memberId);
            syncService.recordEvent(
                    memberId,
                    syncSeq,
                    "MESSAGE_RECALLED",
                    message.messageId(),
                    message.conversationId());
        }
        return recalled;
    }

    MessageRecord moderate(
            UUID moderatorId,
            UUID messageId,
            ModerateMessageRequest request
    ) {
        UUID conversationId = repository.findConversationId(messageId);
        if (conversationId == null || !repository.isGroupConversation(conversationId)) {
            throw new MessageException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        if (repository.lockGroupConversation(conversationId) == null) {
            throw new MessageException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        String role = repository.groupMemberRole(conversationId, moderatorId);
        if (!"OWNER".equals(role) && !"ADMIN".equals(role)) {
            throw new MessageException(ApiErrorDefinition.FORBIDDEN_ROLE);
        }
        MessageRecord message = repository.findByIdForUpdate(messageId);
        if (message == null || !conversationId.equals(message.conversationId())) {
            throw new MessageException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        if ("MODERATED".equals(message.state())) {
            return message;
        }
        if (!"ACTIVE".equals(message.state()) || "SYSTEM".equals(message.type())) {
            throw new MessageException(ApiErrorDefinition.FORBIDDEN);
        }
        String reason = request == null || request.reason() == null
                ? ""
                : request.reason().trim();
        if (reason.codePointCount(0, reason.length()) > 500) {
            throw new MessageException(ApiErrorDefinition.INVALID_REQUEST);
        }
        if (message.mediaId() != null) {
            mediaService.expireBoundMedia(message.messageId());
        }
        Instant now = clock.instant();
        repository.moderateMessage(messageId, moderatorId, reason, now);
        MessageRecord moderated = repository.findById(messageId);
        for (UUID memberId : repository.groupActiveMemberIds(conversationId)
                .stream()
                .sorted()
                .toList()) {
            long syncSeq = syncService.allocateSequence(memberId);
            syncService.recordEvent(
                    memberId,
                    syncSeq,
                    "MESSAGE_MODERATED",
                    message.messageId(),
                    conversationId);
        }
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.MESSAGE_MODERATION,
                AuditOutcome.SUCCEEDED,
                moderatorId,
                null,
                AuditSubjectType.MESSAGE,
                messageId,
                null,
                null,
                now));
        return moderated;
    }

    private void recordSystemMessage(
            UUID conversationId,
            UUID actorId,
            String eventType,
            UUID targetUserId,
            String role
    ) {
        if (repository.lockGroupConversation(conversationId) == null) {
            throw new MessageException(ApiErrorDefinition.NOT_MEMBER);
        }
        long sequence = repository.nextConversationSequence(conversationId);
        MessageRecord message = repository.insertSystemMessage(
                UuidV7.random(),
                conversationId,
                sequence,
                actorId,
                UUID.randomUUID(),
                clock.instant(),
                eventType,
                targetUserId,
                role);
        recordMessageCreated(message);
    }

    private void recordMembershipRevoked(UUID conversationId, UUID userId) {
        long syncSeq = syncService.allocateSequence(userId);
        syncService.recordEvent(
                userId,
                syncSeq,
                "MEMBERSHIP_REVOKED",
                conversationId,
                conversationId);
    }

    private void recordMembershipGranted(UUID conversationId, UUID userId) {
        long syncSeq = syncService.allocateSequence(userId);
        syncService.recordEvent(
                userId,
                syncSeq,
                "MEMBERSHIP_GRANTED",
                conversationId,
                conversationId);
    }

    private void recordMessageCreated(MessageRecord message) {
        List<UUID> memberIds = repository.groupActiveMemberIds(message.conversationId());
        for (UUID memberId : memberIds.stream().sorted().toList()) {
            long syncSeq = syncService.allocateSequence(memberId);
            syncService.recordEvent(
                    memberId,
                    syncSeq,
                    "MESSAGE_CREATED",
                    message.messageId(),
                    message.conversationId());
        }
    }
}
