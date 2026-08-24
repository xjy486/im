package com.jitong.im.message;

import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ReadStateService {

    private final ReadStateRepository repository;
    private final SyncService syncService;

    ReadStateService(
            ReadStateRepository repository,
            SyncService syncService
    ) {
        this.repository = repository;
        this.syncService = syncService;
    }

    @Transactional
    public ConversationReadStatePage markRead(
            UUID userId,
            UUID conversationId,
            long readSeq
    ) {
        if (readSeq < 0) {
            throw new MessageException(ApiErrorDefinition.INVALID_REQUEST);
        }
        ReadStateRepository.ConversationTarget target =
                repository.lockConversation(conversationId, userId);
        if (target == null) {
            throw new MessageException(ApiErrorDefinition.NOT_CONTACT);
        }
        if (readSeq > target.lastSequence()) {
            throw new MessageException(ApiErrorDefinition.INVALID_REQUEST);
        }

        long currentReadSeq = repository.currentReadSeq(target, userId);
        if (readSeq > currentReadSeq) {
            repository.upsertReadSeq(target, userId, readSeq);
            for (UUID participantId : target.readEventRecipients().stream().sorted().toList()) {
                long syncSeq = syncService.allocateSequence(participantId);
                syncService.recordEvent(
                        participantId,
                        syncSeq,
                        "CONVERSATION_READ",
                        userId,
                        conversationId);
            }
        }
        return statePage(target, userId);
    }

    @Transactional(readOnly = true)
    public ConversationReadStatePage states(UUID userId, UUID conversationId) {
        ReadStateRepository.ConversationTarget target =
                repository.findConversation(conversationId, userId);
        if (target == null) {
            throw new MessageException(ApiErrorDefinition.NOT_CONTACT);
        }
        return statePage(target, userId);
    }

    private ConversationReadStatePage statePage(
            ReadStateRepository.ConversationTarget target,
            UUID userId
    ) {
        return new ConversationReadStatePage(
                1,
                target.conversationId(),
                List.copyOf(repository.listStates(target, userId)));
    }
}
