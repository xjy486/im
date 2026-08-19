package com.jitong.im.message;

import com.jitong.im.contact.ContactService;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public MessageService(
            MessageRepository repository,
            ContactService contactService,
            ApplicationEventPublisher eventPublisher
    ) {
        this(repository, contactService, eventPublisher, Clock.systemUTC());
    }

    MessageService(
            MessageRepository repository,
            ContactService contactService,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.repository = repository;
        this.contactService = contactService;
        this.eventPublisher = eventPublisher;
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
                    || !previous.text().equals(text)
                    || !"TEXT".equals(previous.type())) {
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
        eventPublisher.publishEvent(new MessageAcceptedEvent(message));
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
