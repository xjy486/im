package com.jitong.im.message;

import com.jitong.im.contact.ContactService;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MessageServiceTest {

    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID RECIPIENT_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final UUID CLIENT_MESSAGE_ID = UUID.randomUUID();

    @Test
    void assigns_the_next_conversation_sequence_after_contact_permission_is_checked() {
        MessageRepository repository = mock(MessageRepository.class);
        ContactService contacts = mock(ContactService.class);
        SyncService sync = mock(SyncService.class);
        MessageService service = new MessageService(
                repository,
                contacts,
                sync,
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC));
        MessageRepository.ConversationTarget target =
                new MessageRepository.ConversationTarget(CONVERSATION_ID, RECIPIENT_ID, "ACTIVE");
        MessageRecord expected = new MessageRecord(
                UUID.randomUUID(),
                CONVERSATION_ID,
                SENDER_ID,
                CLIENT_MESSAGE_ID,
                7,
                "TEXT",
                "ACTIVE",
                "hello",
                Instant.parse("2026-08-20T00:00:00Z"));

        when(repository.lockConversation(CONVERSATION_ID, SENDER_ID)).thenReturn(target);
        when(repository.findByClientMessageId(SENDER_ID, CLIENT_MESSAGE_ID)).thenReturn(null);
        when(contacts.canSendC2c(SENDER_ID, RECIPIENT_ID)).thenReturn(true);
        when(repository.nextConversationSequence(CONVERSATION_ID)).thenReturn(7L);
        when(repository.insertTextMessage(
                any(), eq(CONVERSATION_ID), eq(7L), eq(SENDER_ID), eq(CLIENT_MESSAGE_ID), eq("hello"), any()))
                .thenReturn(expected);

        MessageSendResult result = service.sendText(
                SENDER_ID, CONVERSATION_ID, CLIENT_MESSAGE_ID, "hello");

        assertThat(result.created()).isTrue();
        assertThat(result.message()).isEqualTo(expected);
        verify(repository).nextConversationSequence(CONVERSATION_ID);
    }

    @Test
    void repeats_with_the_same_client_message_id_return_the_existing_message_without_a_new_sequence() {
        MessageRepository repository = mock(MessageRepository.class);
        ContactService contacts = mock(ContactService.class);
        SyncService sync = mock(SyncService.class);
        MessageService service = new MessageService(repository, contacts, sync, Clock.systemUTC());
        MessageRepository.ConversationTarget target =
                new MessageRepository.ConversationTarget(CONVERSATION_ID, RECIPIENT_ID, "ACTIVE");
        MessageRecord existing = new MessageRecord(
                UUID.randomUUID(),
                CONVERSATION_ID,
                SENDER_ID,
                CLIENT_MESSAGE_ID,
                3,
                "TEXT",
                "ACTIVE",
                "hello",
                Instant.now());

        when(repository.lockConversation(CONVERSATION_ID, SENDER_ID)).thenReturn(target);
        when(contacts.canSendC2c(SENDER_ID, RECIPIENT_ID)).thenReturn(true);
        when(repository.findByClientMessageId(SENDER_ID, CLIENT_MESSAGE_ID)).thenReturn(existing);

        MessageSendResult result = service.sendText(
                SENDER_ID, CONVERSATION_ID, CLIENT_MESSAGE_ID, "hello");

        assertThat(result.created()).isFalse();
        assertThat(result.message()).isEqualTo(existing);
        verify(repository, never()).nextConversationSequence(any());
        verify(repository, never()).insertTextMessage(
                any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void rejects_messages_when_the_contact_relationship_is_not_active() {
        MessageRepository repository = mock(MessageRepository.class);
        ContactService contacts = mock(ContactService.class);
        SyncService sync = mock(SyncService.class);
        MessageService service = new MessageService(repository, contacts, sync, Clock.systemUTC());
        MessageRepository.ConversationTarget target =
                new MessageRepository.ConversationTarget(CONVERSATION_ID, RECIPIENT_ID, "ACTIVE");

        when(repository.lockConversation(CONVERSATION_ID, SENDER_ID)).thenReturn(target);
        when(contacts.canSendC2c(SENDER_ID, RECIPIENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.sendText(
                SENDER_ID, CONVERSATION_ID, CLIENT_MESSAGE_ID, "hello"))
                .isInstanceOf(MessageException.class)
                .extracting(exception -> ((MessageException) exception).definition())
                .isEqualTo(ApiErrorDefinition.NOT_CONTACT);
        verify(repository, never()).nextConversationSequence(any());
    }

    @Test
    void writes_one_sync_event_for_each_conversation_participant() {
        MessageRepository repository = mock(MessageRepository.class);
        ContactService contacts = mock(ContactService.class);
        SyncService sync = mock(SyncService.class);
        MessageService service = new MessageService(
                repository,
                contacts,
                sync,
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC));
        MessageRepository.ConversationTarget target =
                new MessageRepository.ConversationTarget(CONVERSATION_ID, RECIPIENT_ID, "ACTIVE");
        MessageRecord message = new MessageRecord(
                UUID.randomUUID(),
                CONVERSATION_ID,
                SENDER_ID,
                CLIENT_MESSAGE_ID,
                1,
                "TEXT",
                "ACTIVE",
                "hello",
                Instant.parse("2026-08-20T00:00:00Z"));

        when(repository.lockConversation(CONVERSATION_ID, SENDER_ID)).thenReturn(target);
        when(repository.findByClientMessageId(SENDER_ID, CLIENT_MESSAGE_ID)).thenReturn(null);
        when(contacts.canSendC2c(SENDER_ID, RECIPIENT_ID)).thenReturn(true);
        when(repository.nextConversationSequence(CONVERSATION_ID)).thenReturn(1L);
        when(repository.insertTextMessage(
                any(), eq(CONVERSATION_ID), eq(1L), eq(SENDER_ID), eq(CLIENT_MESSAGE_ID), eq("hello"), any()))
                .thenReturn(message);
        when(repository.conversationParticipants(CONVERSATION_ID))
                .thenReturn(List.of(RECIPIENT_ID, SENDER_ID));
        when(sync.allocateSequence(SENDER_ID)).thenReturn(7L);
        when(sync.allocateSequence(RECIPIENT_ID)).thenReturn(11L);

        service.sendText(SENDER_ID, CONVERSATION_ID, CLIENT_MESSAGE_ID, "hello");

        verify(sync).recordEvent(SENDER_ID, 7L, "MESSAGE_CREATED", message.messageId(), CONVERSATION_ID);
        verify(sync).recordEvent(RECIPIENT_ID, 11L, "MESSAGE_CREATED", message.messageId(), CONVERSATION_ID);
        verify(sync, times(2)).allocateSequence(any());
    }
}
