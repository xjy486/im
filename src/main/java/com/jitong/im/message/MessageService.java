package com.jitong.im.message;

import com.jitong.im.contact.ContactService;
import com.jitong.im.media.MediaService;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class MessageService {

    private final MessageRepository repository;
    private final ContactService contactService;
    private final SyncService syncService;
    private final MediaService mediaService;
    private final Clock clock;

    @Autowired
    public MessageService(
            MessageRepository repository,
            ContactService contactService,
            SyncService syncService,
            MediaService mediaService
    ) {
        this(repository, contactService, syncService, mediaService, Clock.systemUTC());
    }

    MessageService(
            MessageRepository repository,
            ContactService contactService,
            SyncService syncService,
            Clock clock
    ) {
        this(repository, contactService, syncService, null, clock);
    }

    MessageService(
            MessageRepository repository,
            ContactService contactService,
            SyncService syncService,
            MediaService mediaService,
            Clock clock
    ) {
        this.repository = repository;
        this.contactService = contactService;
        this.syncService = syncService;
        this.mediaService = mediaService;
        this.clock = clock;
    }

    @Transactional
    public MessageSendResult sendText(
            UUID senderId,
            UUID conversationId,
        UUID clientMsgId,
        String text
    ) {
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
        MessageRepository.ConversationTarget target = repository.findConversation(conversationId, userId);
        if (target == null) {
            throw new MessageException(ApiErrorDefinition.NOT_CONTACT);
        }
        return new ConversationMessagePage(
                1,
                conversationId,
                List.copyOf(repository.listMessages(conversationId, afterSequence, limit)));
    }

    MessageRepository.ConversationTarget target(UUID conversationId, UUID userId) {
        return repository.findConversation(conversationId, userId);
    }

}
