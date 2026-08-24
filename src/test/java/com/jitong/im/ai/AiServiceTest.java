package com.jitong.im.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.auth.AuthenticatedDevice;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                new AiProperties.Worker(250),
                null);
        AiService service = new AiService(
                repository,
                syncService,
                properties,
                new ObjectMapper(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        when(repository.findByRequest(USER_ID, UUID.randomUUID())).thenReturn(null);

        UUID requestId = UUID.randomUUID();
        when(repository.lockActiveOwnerForUpdate(USER_ID)).thenReturn(true);
        when(repository.findByRequest(USER_ID, requestId)).thenReturn(null);
        when(repository.findConversationForUpdate(CONVERSATION_ID, USER_ID))
                .thenReturn(new AiConversation(
                        CONVERSATION_ID,
                        USER_ID,
                        UUID.randomUUID(),
                        "C2C",
                        "ACTIVE",
                        3,
                        0,
                        1,
                        0,
                        0,
                        false,
                        true));

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
                new AiProperties.Worker(250),
                null);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AiService service = new AiService(
                repository,
                syncService,
                properties,
                mapper,
                Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));
        UUID requestId = UUID.randomUUID();
        UUID firstMessage = UUID.randomUUID();
        when(repository.lockActiveOwnerForUpdate(USER_ID)).thenReturn(true);
        when(repository.findByRequest(USER_ID, requestId)).thenReturn(null);
        when(repository.findConversationForUpdate(CONVERSATION_ID, USER_ID))
                .thenReturn(new AiConversation(
                        CONVERSATION_ID,
                        USER_ID,
                        UUID.randomUUID(),
                        "C2C",
                        "ACTIVE",
                        4,
                        0,
                        2,
                        0,
                        0,
                        true,
                        true));
        when(repository.listContext(CONVERSATION_ID, 0, 4, 100))
                .thenReturn(List.of(new AiContextMessage(
                        firstMessage,
                        2,
                        USER_ID,
                        "authorized")));
        when(repository.reserveBudget(
                eq(USER_ID),
                eq(LocalDate.of(2026, 8, 23)),
                eq(100_000L),
                anyLong())).thenReturn(true);
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
                        0,
                        "cache-key",
                        LocalDate.of(2026, 8, 23),
                        0,
                        0,
                        0,
                        0,
                        "test-model",
                        "summary-v1",
                        false,
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
                eq("SUMMARY"),
                eq(2L),
                eq(2L),
                any(),
                any(),
                eq(2L),
                eq(0L),
                any(),
                eq(LocalDate.of(2026, 8, 23)),
                anyLong(),
                eq("test-model"),
                eq("summary-v1"),
                eq(false),
                any());
        verify(syncService).recordEventForUsers(
                eq(List.of(USER_ID)),
                eq("AI_JOB_QUEUED"),
                any(),
                eq(CONVERSATION_ID));
        var ordered = inOrder(repository);
        ordered.verify(repository).lockActiveOwnerForUpdate(USER_ID);
        ordered.verify(repository).findConversationForUpdate(CONVERSATION_ID, USER_ID);
    }

    @Test
    void refuses_to_enqueue_after_account_retirement_wins_the_owner_lock() {
        AiRepository repository = mock(AiRepository.class);
        SyncService syncService = mock(SyncService.class);
        AiService service = service(repository, syncService);
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> service.enqueueSummary(
                new AuthenticatedDevice(USER_ID, DEVICE_ID, "MOBILE"),
                CONVERSATION_ID,
                new AiSummaryRequest(requestId, 0L, 1L)))
                .isInstanceOf(AiException.class)
                .extracting(exception -> ((AiException) exception).definition())
                .isEqualTo(ApiErrorDefinition.AUTH_INVALID);

        verify(repository).lockActiveOwnerForUpdate(USER_ID);
        verify(repository, never()).findConversationForUpdate(CONVERSATION_ID, USER_ID);
    }

    @Test
    void artifact_deletion_shares_the_conversation_lock_with_cache_hits() {
        AiRepository repository = mock(AiRepository.class);
        SyncService syncService = mock(SyncService.class);
        AiService service = service(repository, syncService);
        UUID jobId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        AiRepository.AiArtifactDeletionRecord artifact =
                new AiRepository.AiArtifactDeletionRecord(
                        artifactId,
                        jobId,
                        USER_ID,
                        CONVERSATION_ID,
                        "cache-key");
        when(repository.findArtifactDeletionContext(USER_ID, artifactId)).thenReturn(artifact);
        when(repository.findJobsForContentDeletionForUpdate(USER_ID, jobId, "cache-key"))
                .thenReturn(List.of());
        when(repository.findArtifactsForContentDeletionForUpdate(USER_ID, jobId, "cache-key"))
                .thenReturn(List.of(artifact));

        service.deleteArtifact(USER_ID, artifactId);

        var ordered = inOrder(repository);
        ordered.verify(repository).findArtifactDeletionContext(USER_ID, artifactId);
        ordered.verify(repository).findConversationForUpdate(CONVERSATION_ID, USER_ID);
        ordered.verify(repository).findJobsForContentDeletionForUpdate(USER_ID, jobId, "cache-key");
    }

    @Test
    void job_deletion_shares_the_conversation_lock_with_cache_hits() {
        AiRepository repository = mock(AiRepository.class);
        SyncService syncService = mock(SyncService.class);
        AiService service = service(repository, syncService);
        UUID jobId = UUID.randomUUID();
        AiJobRecord job = job(jobId);
        when(repository.findJob(USER_ID, jobId)).thenReturn(job);
        when(repository.findJobForUpdate(USER_ID, jobId)).thenReturn(job);
        when(repository.deleteJob(USER_ID, jobId)).thenReturn(1);

        service.deleteJob(USER_ID, jobId);

        var ordered = inOrder(repository);
        ordered.verify(repository).findJob(USER_ID, jobId);
        ordered.verify(repository).findConversationForUpdate(CONVERSATION_ID, USER_ID);
        ordered.verify(repository).findJobForUpdate(USER_ID, jobId);
    }

    private AiService service(AiRepository repository, SyncService syncService) {
        return new AiService(
                repository,
                syncService,
                new AiProperties(
                        "summary-v1",
                        new AiProperties.Provider(
                                true,
                                "http://provider.test/v1",
                                "secret",
                                "test-model"),
                        new AiProperties.Worker(250),
                        null),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private AiJobRecord job(UUID jobId) {
        return new AiJobRecord(
                jobId,
                USER_ID,
                DEVICE_ID,
                CONVERSATION_ID,
                UUID.randomUUID(),
                "SUMMARY",
                "SUCCEEDED",
                1,
                1,
                "digest",
                null,
                1,
                0,
                "cache-key",
                LocalDate.of(1970, 1, 1),
                0,
                1,
                1,
                1,
                "test-model",
                "summary-v1",
                false,
                "{}",
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60));
    }
}
