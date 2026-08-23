package com.jitong.im.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.auth.AuthenticatedDevice;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DEVICE_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();

    @Test
    void refuses_to_enqueue_a_summary_until_both_c2c_users_have_enabled_ai() {
        AiRepository repository = mock(AiRepository.class);
        SyncService syncService = mock(SyncService.class);
        AiProperties properties = new AiProperties(
                "summary-v1",
                new AiProperties.Provider(true, "http://provider.test/v1", "secret", "test-model"),
                new AiProperties.Worker(250));
        AiService service = new AiService(
                repository,
                syncService,
                properties,
                new ObjectMapper(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        when(repository.findByRequest(USER_ID, UUID.randomUUID())).thenReturn(null);

        UUID requestId = UUID.randomUUID();
        when(repository.findByRequest(USER_ID, requestId)).thenReturn(null);
        when(repository.findConversationForUpdate(CONVERSATION_ID, USER_ID))
                .thenReturn(new AiConversation(
                        CONVERSATION_ID,
                        USER_ID,
                        UUID.randomUUID(),
                        "ACTIVE",
                        3,
                        0,
                        1,
                        true,
                        false));

        assertThatThrownBy(() -> service.enqueueSummary(
                new AuthenticatedDevice(USER_ID, DEVICE_ID, "MOBILE"),
                CONVERSATION_ID,
                new AiSummaryRequest(requestId, 0L, 3L)))
                .isInstanceOf(AiException.class)
                .extracting(exception -> ((AiException) exception).definition())
                .isEqualTo(ApiErrorDefinition.AI_CONSENT_REQUIRED);
    }

    @Test
    void persists_only_active_text_messages_in_the_requested_context() {
        AiRepository repository = mock(AiRepository.class);
        SyncService syncService = mock(SyncService.class);
        AiProperties properties = new AiProperties(
                "summary-v1",
                new AiProperties.Provider(true, "http://provider.test/v1", "secret", "test-model"),
                new AiProperties.Worker(250));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AiService service = new AiService(
                repository,
                syncService,
                properties,
                mapper,
                Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));
        UUID requestId = UUID.randomUUID();
        UUID firstMessage = UUID.randomUUID();
        when(repository.findByRequest(USER_ID, requestId)).thenReturn(null);
        when(repository.findConversationForUpdate(CONVERSATION_ID, USER_ID))
                .thenReturn(new AiConversation(
                        CONVERSATION_ID,
                        USER_ID,
                        UUID.randomUUID(),
                        "ACTIVE",
                        4,
                        0,
                        2,
                        true,
                        true));
        when(repository.listContext(CONVERSATION_ID, 0, 4, 100))
                .thenReturn(List.of(new AiContextMessage(
                        firstMessage,
                        2,
                        USER_ID,
                        "authorized")));
        when(repository.findJob(eq(USER_ID), any(UUID.class)))
                .thenReturn(new AiJobRecord(
                        UUID.randomUUID(),
                        USER_ID,
                        DEVICE_ID,
                        CONVERSATION_ID,
                        requestId,
                        "SUMMARY",
                        "QUEUED",
                        2,
                        2,
                        "digest",
                        "[]",
                        2,
                        "test-model",
                        "summary-v1",
                        null,
                        null,
                        Instant.EPOCH,
                        null,
                        null,
                        Instant.parse("2026-09-22T00:00:00Z")));

        service.enqueueSummary(
                new AuthenticatedDevice(USER_ID, DEVICE_ID, "MOBILE"),
                CONVERSATION_ID,
                new AiSummaryRequest(requestId, 0L, 4L));

        verify(repository).insertJob(
                any(),
                eq(USER_ID),
                eq(DEVICE_ID),
                eq(CONVERSATION_ID),
                eq(requestId),
                eq(2L),
                eq(2L),
                any(),
                any(),
                eq(2L),
                eq("test-model"),
                eq("summary-v1"),
                any());
        verify(syncService).recordEventForUsers(
                eq(List.of(USER_ID)),
                eq("AI_JOB_QUEUED"),
                any(),
                eq(CONVERSATION_ID));
    }
}
