package com.jitong.im.message;

import com.jitong.im.auth.UuidV7;
import com.jitong.im.sync.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Persists the C2C system message emitted when a contact relationship becomes
 * active.
 */
@Service
public class ContactMessageService {

    private final MessageRepository repository;
    private final SyncService syncService;
    private final Clock clock;

    @Autowired
    public ContactMessageService(
            MessageRepository repository,
            SyncService syncService
    ) {
        this(repository, syncService, Clock.systemUTC());
    }

    ContactMessageService(
            MessageRepository repository,
            SyncService syncService,
            Clock clock
    ) {
        this.repository = repository;
        this.syncService = syncService;
        this.clock = clock;
    }

    public MessageRecord recordEstablished(
            UUID conversationId,
            UUID firstUserId,
            UUID secondUserId,
            Instant acceptedAt
    ) {
        long sequence = repository.nextConversationSequence(conversationId);
        MessageRecord message = repository.insertSystemMessage(
                UuidV7.random(),
                conversationId,
                sequence,
                firstUserId,
                UUID.randomUUID(),
                acceptedAt == null ? clock.instant() : acceptedAt,
                "CONTACT_ESTABLISHED",
                secondUserId,
                null);
        for (UUID participantId : repository.conversationParticipants(conversationId)
                .stream()
                .sorted()
                .toList()) {
            long syncSeq = syncService.allocateSequence(participantId);
            syncService.recordEvent(
                    participantId,
                    syncSeq,
                    "MESSAGE_CREATED",
                    message.messageId(),
                    conversationId);
        }
        return message;
    }
}
