package com.jitong.im.media;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaCleanupJobTest {

    @Test
    void expires_old_temp_media_and_deletes_both_normalized_objects() {
        MediaRepository repository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        UUID mediaId = UUID.randomUUID();
        MediaRecord candidate = new MediaRecord(
                mediaId,
                "MESSAGE_IMAGE",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "TEMP",
                "message-images/full.jpg",
                "message-images/thumb.jpg",
                "image/jpeg",
                100,
                80,
                1000,
                "a".repeat(64),
                null,
                Instant.now().minusSeconds(90_000),
                null,
                null,
                null);
        when(repository.findCleanupCandidates(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(candidate));

        new MediaCleanupJob(repository, storage).cleanExpiredTemporaryMedia();

        verify(repository).markExpired(org.mockito.ArgumentMatchers.eq(mediaId), org.mockito.ArgumentMatchers.any());
        verify(storage).delete("message-images/full.jpg");
        verify(storage).delete("message-images/thumb.jpg");
        verify(repository).markObjectsDeleted(
                org.mockito.ArgumentMatchers.eq(mediaId),
                org.mockito.ArgumentMatchers.any());
    }
}
