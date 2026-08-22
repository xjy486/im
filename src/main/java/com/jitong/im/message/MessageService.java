package com.jitong.im.message;

import com.jitong.im.contact.ContactService;
import com.jitong.im.media.MediaService;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class MessageService {

    private final MessageRepository repository;
    private final ContactService contactService;
    private final SyncService syncService;
    private final MediaService mediaService;
    private final GroupMessageService groupMessageService;
    private final Clock clock;

    @Autowired
    public MessageService(
            MessageRepository repository,
            ContactService contactService,
            SyncService syncService,
            MediaService mediaService,
            GroupMessageService groupMessageService
    ) {
        this(repository, contactService, syncService, mediaService, groupMessageService, Clock.systemUTC());
    }

    MessageService(
            MessageRepository repository,
            ContactService contactService,
            SyncService syncService,
            Clock clock
    ) {
        this(repository, contactService, syncService, null, null, clock);
    }

    MessageService(
            MessageRepository repository,
            ContactService contactService,
            SyncService syncService,
            MediaService mediaService,
            Clock clock
    ) {
        this(repository, contactService, syncService, mediaService, null, clock);
    }

    MessageService(
            MessageRepository repository,
            ContactService contactService,
            SyncService syncService,
            MediaService mediaService,
            GroupMessageService groupMessageService,
            Clock clock
    ) {
        this.repository = repository;
        this.contactService = contactService;
        this.syncService = syncService;
        this.mediaService = mediaService;
        this.groupMessageService = groupMessageService;
        this.clock = clock;
    }

    @Transactional
    public MessageSendResult sendText(
            UUID senderId,
            UUID conversationId,
        UUID clientMsgId,
        String text
    ) {
        if (groupMessageService != null && repository.isGroupConversation(conversationId)) {
            return groupMessageService.sendText(senderId, conversationId, clientMsgId, text);
        }
        MessagePayloadValidator.validateText(text);
        MessagePayloadValidator.validateClientMessageId(clientMsgId);
        MessageRepository.ConversationTarget target = repository.lockConversation(conversationId, senderId);
        if (target == null
                || !"ACTIVE".equals(target.status())
                || !contactService.canSendC2c(senderId, target.peerUserId())) {
            throw new MessageException(ApiErrorDefinition.NOT_CONTACT);
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
                com.jitong.im.auth.UuidV7.random(),
                conversationId,
                sequence,
                senderId,
                clientMsgId,
                text,
                clock.instant());
        for (UUID participantId : repository.conversationParticipants(conversationId).stream().sorted().toList()) {
            long syncSeq = syncService.allocateSequence(participantId);
            syncService.recordEvent(
                    participantId,
                    syncSeq,
                    "MESSAGE_CREATED",
                    message.messageId(),
                    conversationId);
        }
        return new MessageSendResult(message, true);
    }

    @Transactional
    public MessageSendResult sendImage(
            UUID senderId,
            UUID conversationId,
            UUID clientMsgId,
            UUID mediaId
    ) {
        if (groupMessageService != null && repository.isGroupConversation(conversationId)) {
            return groupMessageService.sendImage(senderId, conversationId, clientMsgId, mediaId);
        }
        MessagePayloadValidator.validateClientMessageId(clientMsgId);
        if (mediaId == null || mediaService == null) {
            throw new MessageException(ApiErrorDefinition.MEDIA_INVALID);
        }
        MessageRepository.ConversationTarget target = repository.lockConversation(conversationId, senderId);
        if (target == null
                || !"ACTIVE".equals(target.status())
                || !contactService.canSendC2c(senderId, target.peerUserId())) {
            throw new MessageException(ApiErrorDefinition.NOT_CONTACT);
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

        UUID messageId = com.jitong.im.auth.UuidV7.random();
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
        for (UUID participantId : repository.conversationParticipants(conversationId).stream().sorted().toList()) {
            long syncSeq = syncService.allocateSequence(participantId);
            syncService.recordEvent(
                    participantId,
                    syncSeq,
                    "MESSAGE_CREATED",
                    message.messageId(),
                    conversationId);
        }
        return new MessageSendResult(message, true);
    }

    @Transactional(readOnly = true)
    public ConversationMessagePage listMessages(
            UUID userId,
            UUID conversationId,
            long afterSequence,
            int limit
    ) {
        if (afterSequence < 0 || limit < 1 || limit > 200) {
            throw new MessageException(ApiErrorDefinition.INVALID_REQUEST);
        }
        if (groupMessageService != null && repository.isGroupConversation(conversationId)) {
            return groupMessageService.listMessages(userId, conversationId, afterSequence, limit);
        }
        MessageRepository.ConversationTarget target = repository.findConversation(conversationId, userId);
        if (target == null) {
            throw new MessageException(ApiErrorDefinition.NOT_CONTACT);
        }
        return new ConversationMessagePage(
                1,
                conversationId,
                List.copyOf(repository.listMessages(conversationId, afterSequence, limit)));
    }

    @Transactional
    public MessageRecord recall(UUID senderId, UUID messageId) {
        UUID conversationId = repository.findConversationId(messageId);
        if (conversationId == null) {
            throw new MessageException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        if (groupMessageService != null && repository.isGroupConversation(conversationId)) {
            return groupMessageService.recall(senderId, messageId);
        }
        if (repository.lockConversation(conversationId, senderId) == null) {
            throw new MessageException(ApiErrorDefinition.FORBIDDEN);
        }
        MessageRecord message = repository.findByIdForUpdate(messageId);
        if (message == null || !senderId.equals(message.senderId())) {
            throw new MessageException(ApiErrorDefinition.FORBIDDEN);
        }
        if ("RECALLED".equals(message.state())) {
            return message;
        }
        if (!"ACTIVE".equals(message.state())) {
            throw new MessageException(ApiErrorDefinition.FORBIDDEN);
        }
        Instant now = clock.instant();
        if (!now.isBefore(message.serverAcceptedAt().plus(Duration.ofSeconds(60)))) {
            throw new MessageException(ApiErrorDefinition.RECALL_WINDOW_EXPIRED);
        }
        if (message.mediaId() != null && mediaService != null) {
            mediaService.expireBoundMedia(message.messageId());
        }
        repository.recallMessage(messageId, now);
        MessageRecord recalled = repository.findById(messageId);
        for (UUID participantId : repository.conversationParticipants(message.conversationId()).stream().sorted().toList()) {
            long syncSeq = syncService.allocateSequence(participantId);
            syncService.recordEvent(
                    participantId,
                    syncSeq,
                    "MESSAGE_RECALLED",
                    message.messageId(),
                    message.conversationId());
        }
        return recalled;
    }

    @Transactional
    public MessageRecord moderate(
            UUID moderatorId,
            UUID messageId,
            ModerateMessageRequest request
    ) {
        UUID conversationId = repository.findConversationId(messageId);
        if (conversationId == null) {
            throw new MessageException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        if (groupMessageService == null || !repository.isGroupConversation(conversationId)) {
            throw new MessageException(ApiErrorDefinition.FORBIDDEN);
        }
        return groupMessageService.moderate(moderatorId, messageId, request);
    }

    MessageRepository.ConversationTarget target(UUID conversationId, UUID userId) {
        return repository.findConversation(conversationId, userId);
    }

}
