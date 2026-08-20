package com.jitong.im.sync;

import com.jitong.im.platform.error.ApiErrorDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SyncServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DEVICE_ID = UUID.randomUUID();

    @Test
    void pages_events_at_a_fixed_high_watermark_without_advancing_the_cursor() {
        SyncRepository repository = mock(SyncRepository.class);
        SyncService service = new SyncService(repository);
        UUID firstMessage = UUID.randomUUID();
        UUID secondMessage = UUID.randomUUID();
        when(repository.currentHighWatermark(USER_ID)).thenReturn(3L);
        when(repository.retainedWindowStart(USER_ID)).thenReturn(1L);
        when(repository.listEvents(USER_ID, 1L, 3L, 200)).thenReturn(List.of(
                new SyncEventRecord(2L, "MESSAGE_CREATED", firstMessage, UUID.randomUUID(), Instant.EPOCH),
                new SyncEventRecord(3L, "MESSAGE_CREATED", secondMessage, UUID.randomUUID(), Instant.EPOCH)));

        SyncPage page = service.page(USER_ID, 1L, 3L, 200);

        assertThat(page.highWatermark()).isEqualTo(3L);
        assertThat(page.events()).extracting(SyncEventRecord::syncSeq).containsExactly(2L, 3L);
        verify(repository, never()).acknowledge(any(), any(), anyLong());
    }

    @Test
    void rejects_a_cursor_before_the_retained_window() {
        SyncRepository repository = mock(SyncRepository.class);
        SyncService service = new SyncService(repository);
        when(repository.currentHighWatermark(USER_ID)).thenReturn(9L);
        when(repository.retainedWindowStart(USER_ID)).thenReturn(5L);

        assertThatThrownBy(() -> service.page(USER_ID, 1L, null, 200))
                .isInstanceOf(SyncException.class)
                .extracting(exception -> ((SyncException) exception).definition())
                .isEqualTo(ApiErrorDefinition.SYNC_RESET_REQUIRED);
    }

    @Test
    void allows_a_watermark_probe_without_reading_an_expired_cursor() {
        SyncRepository repository = mock(SyncRepository.class);
        SyncService service = new SyncService(repository);
        when(repository.currentHighWatermark(USER_ID)).thenReturn(12L);
        when(repository.retainedWindowStart(USER_ID)).thenReturn(8L);
        when(repository.listEvents(USER_ID, 0L, 0L, 200)).thenReturn(List.of());

        SyncPage page = service.page(USER_ID, 0L, 0L, 200);

        assertThat(page.highWatermark()).isEqualTo(12L);
        assertThat(page.events()).isEmpty();
    }

    @Test
    void does_not_allow_a_device_to_ack_beyond_the_current_user_high_watermark() {
        SyncRepository repository = mock(SyncRepository.class);
        SyncService service = new SyncService(repository);
        when(repository.currentHighWatermark(USER_ID)).thenReturn(4L);

        assertThatThrownBy(() -> service.acknowledge(
                new com.jitong.im.auth.AuthenticatedDevice(USER_ID, DEVICE_ID, "MOBILE"),
                5L))
                .isInstanceOf(SyncException.class)
                .extracting(exception -> ((SyncException) exception).definition())
                .isEqualTo(ApiErrorDefinition.INVALID_REQUEST);
        verify(repository, never()).acknowledge(any(), any(), anyLong());
    }

    @Test
    void keeps_mobile_and_pc_acknowledgements_independent() {
        SyncRepository repository = mock(SyncRepository.class);
        SyncService service = new SyncService(repository);
        UUID mobileDeviceId = UUID.randomUUID();
        UUID pcDeviceId = UUID.randomUUID();
        when(repository.currentHighWatermark(USER_ID)).thenReturn(4L);
        when(repository.isActiveDevice(USER_ID, mobileDeviceId)).thenReturn(true);
        when(repository.isActiveDevice(USER_ID, pcDeviceId)).thenReturn(true);

        service.acknowledge(
                new com.jitong.im.auth.AuthenticatedDevice(USER_ID, mobileDeviceId, "MOBILE"),
                4L);
        service.acknowledge(
                new com.jitong.im.auth.AuthenticatedDevice(USER_ID, pcDeviceId, "PC"),
                2L);

        verify(repository).acknowledge(USER_ID, mobileDeviceId, 4L);
        verify(repository).acknowledge(USER_ID, pcDeviceId, 2L);
    }

    @Test
    void rejects_a_page_that_skips_a_retained_event() {
        SyncRepository repository = mock(SyncRepository.class);
        SyncService service = new SyncService(repository);
        when(repository.currentHighWatermark(USER_ID)).thenReturn(3L);
        when(repository.retainedWindowStart(USER_ID)).thenReturn(1L);
        when(repository.listEvents(USER_ID, 0L, 3L, 200)).thenReturn(List.of(
                new SyncEventRecord(1L, "MESSAGE_CREATED", UUID.randomUUID(), UUID.randomUUID(), java.time.Instant.EPOCH),
                new SyncEventRecord(3L, "MESSAGE_CREATED", UUID.randomUUID(), UUID.randomUUID(), java.time.Instant.EPOCH)));

        assertThatThrownBy(() -> service.page(USER_ID, 0L, 3L, 200))
                .isInstanceOf(SyncException.class)
                .extracting(exception -> ((SyncException) exception).definition())
                .isEqualTo(ApiErrorDefinition.SYNC_RESET_REQUIRED);
    }
}
