package com.jitong.im.media;

import com.jitong.im.platform.error.ApiErrorDefinition;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaServiceTest {

    @Test
    void rejects_media_bound_to_another_user() {
        MediaRepository repository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        UUID mediaId = UUID.randomUUID();
        when(repository.findByIdForUpdate(mediaId)).thenReturn(new MediaRecord(
                mediaId,
                "MESSAGE_IMAGE",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "TEMP",
                "full",
                "thumb",
                "image/jpeg",
                10,
                10,
                100,
                "a".repeat(64),
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null));

        MediaService service = new MediaService(
                repository,
                storage,
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.bindMessageImage(
                UUID.randomUUID(),
                mediaId,
                UUID.randomUUID()))
                .isInstanceOf(MediaException.class)
                .extracting(exception -> ((MediaException) exception).definition())
                .isEqualTo(ApiErrorDefinition.MEDIA_FORBIDDEN);
    }

    @Test
    void rejects_a_temporary_upload_after_24_hours_before_cleanup_runs() {
        MediaRepository repository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        UUID mediaId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(repository.findAccess(mediaId, ownerId)).thenReturn(new MediaRepository.AccessRecord(
                mediaId,
                "TEMP",
                "original",
                "thumb",
                "image/jpeg",
                ownerId,
                true,
                true));

        MediaService service = new MediaService(
                repository,
                storage,
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.download(ownerId, mediaId, "full"))
                .isInstanceOf(MediaException.class)
                .extracting(exception -> ((MediaException) exception).definition())
                .isEqualTo(ApiErrorDefinition.MEDIA_EXPIRED);
    }

    @Test
    void cannot_bind_a_temporary_upload_after_24_hours() {
        MediaRepository repository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        UUID mediaId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(repository.findByIdForUpdate(mediaId)).thenReturn(new MediaRecord(
                mediaId,
                "MESSAGE_IMAGE",
                ownerId,
                UUID.randomUUID(),
                "TEMP",
                "full",
                "thumb",
                "image/jpeg",
                10,
                10,
                100,
                "a".repeat(64),
                null,
                Instant.parse("2026-08-18T23:59:59Z"),
                null,
                null,
                null,
                null,
                null));

        MediaService service = new MediaService(
                repository,
                storage,
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.bindMessageImage(
                ownerId,
                mediaId,
                UUID.randomUUID()))
                .isInstanceOf(MediaException.class)
                .extracting(exception -> ((MediaException) exception).definition())
                .isEqualTo(ApiErrorDefinition.MEDIA_EXPIRED);
    }
}
