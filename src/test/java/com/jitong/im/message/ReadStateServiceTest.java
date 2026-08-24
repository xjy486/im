package com.jitong.im.message;

import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReadStateServiceTest {

    private static final UUID READER_ID = UUID.randomUUID();
    private static final UUID PEER_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();

    @Test
    void advances_one_user_read_seq_and_records_a_read_event_for_each_participant() {
        ReadStateRepository repository = mock(ReadStateRepository.class);
        SyncService syncService = mock(SyncService.class);
        ReadStateService service = new ReadStateService(repository, syncService);
        when(repository.lockConversation(CONVERSATION_ID, READER_ID))
                .thenReturn(new ReadStateRepository.ConversationTarget(
                        CONVERSATION_ID,
                        7L,
                        ReadStateRepository.ConversationKind.C2C,
                        List.of(READER_ID, PEER_ID)));
        when(repository.currentReadSeq(any(), eq(READER_ID))).thenReturn(2L);
        when(syncService.allocateSequence(READER_ID)).thenReturn(11L);
        when(syncService.allocateSequence(PEER_ID)).thenReturn(13L);
        when(repository.listStates(any(), eq(READER_ID))).thenReturn(List.of(
                new ConversationReadState(CONVERSATION_ID, READER_ID, 7L),
                new ConversationReadState(CONVERSATION_ID, PEER_ID, 0L)));

        ConversationReadStatePage result = service.markRead(READER_ID, CONVERSATION_ID, 7L);

        assertThat(result.states())
                .extracting(ConversationReadState::readSeq)
                .containsExactly(7L, 0L);
        verify(repository).upsertReadSeq(any(), eq(READER_ID), eq(7L));
        verify(syncService).recordEvent(
                READER_ID, 11L, "CONVERSATION_READ", READER_ID, CONVERSATION_ID);
        verify(syncService).recordEvent(
                PEER_ID, 13L, "CONVERSATION_READ", READER_ID, CONVERSATION_ID);
    }

    @Test
    void does_not_move_a_user_read_seq_backwards_or_emit_a_duplicate_event() {
        ReadStateRepository repository = mock(ReadStateRepository.class);
        SyncService syncService = mock(SyncService.class);
        ReadStateService service = new ReadStateService(repository, syncService);
        when(repository.lockConversation(CONVERSATION_ID, READER_ID))
                .thenReturn(new ReadStateRepository.ConversationTarget(
                        CONVERSATION_ID,
                        7L,
                        ReadStateRepository.ConversationKind.C2C,
                        List.of(READER_ID, PEER_ID)));
        when(repository.currentReadSeq(any(), eq(READER_ID))).thenReturn(7L);
        when(repository.listStates(any(), eq(READER_ID))).thenReturn(List.of(
                new ConversationReadState(CONVERSATION_ID, READER_ID, 7L),
                new ConversationReadState(CONVERSATION_ID, PEER_ID, 0L)));

        ConversationReadStatePage result = service.markRead(READER_ID, CONVERSATION_ID, 3L);

        assertThat(result.states().get(0).readSeq()).isEqualTo(7L);
        verify(repository, never()).upsertReadSeq(any(), any(), anyLong());
        verifyNoInteractions(syncService);
    }

    @Test
    void rejects_a_read_seq_beyond_the_authoritative_conversation_sequence() {
        ReadStateRepository repository = mock(ReadStateRepository.class);
        SyncService syncService = mock(SyncService.class);
        ReadStateService service = new ReadStateService(repository, syncService);
        when(repository.lockConversation(CONVERSATION_ID, READER_ID))
                .thenReturn(new ReadStateRepository.ConversationTarget(
                        CONVERSATION_ID,
                        7L,
                        ReadStateRepository.ConversationKind.C2C,
                        List.of(READER_ID, PEER_ID)));

        assertThatThrownBy(() -> service.markRead(READER_ID, CONVERSATION_ID, 8L))
                .isInstanceOf(MessageException.class)
                .extracting(exception -> ((MessageException) exception).definition())
                .isEqualTo(ApiErrorDefinition.INVALID_REQUEST);
        verify(repository, never()).currentReadSeq(any(), any());
    }
}
