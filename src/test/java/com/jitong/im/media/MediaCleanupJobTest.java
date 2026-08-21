package com.jitong.im.media;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class MediaCleanupJobTest {

    @Test
    void expires_old_temp_media_and_deletes_both_normalized_objects() {
        MediaRepository repository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        UUID mediaId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
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
                now.minusSeconds(90_000),
                null,
                null,
                null,
                null,
                null);
        when(repository.findCleanupCandidates(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(candidate));

        new MediaCleanupJob(
                repository,
                storage,
                Clock.fixed(now, ZoneOffset.UTC)).cleanExpiredTemporaryMedia();

        verify(repository).markExpired(org.mockito.ArgumentMatchers.eq(mediaId), org.mockito.ArgumentMatchers.any());
        verify(storage).delete("message-images/full.jpg");
        verify(storage).delete("message-images/thumb.jpg");
        verify(repository).markObjectsDeleted(
                org.mockito.ArgumentMatchers.eq(mediaId),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void scans_managed_prefixes_and_deletes_only_old_unreferenced_objects() {
        MediaRepository repository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        when(repository.findReferencedObjectKeys())
                .thenReturn(Set.of(
                        "avatars/user/known/512.webp",
                        "message-images/known.jpg"));
        when(storage.list("message-images/"))
                .thenReturn(List.of(
                        new MediaStorage.StoredObject(
                                "message-images/orphan/old.jpg",
                                now.minus(Duration.ofDays(2))),
                        new MediaStorage.StoredObject(
                                "message-images/orphan/recent.jpg",
                                now.minus(Duration.ofHours(1))),
                        new MediaStorage.StoredObject(
                                "message-images/orphan/at-cutoff.jpg",
                                now.minus(Duration.ofHours(24))),
                        new MediaStorage.StoredObject(
                                "message-images/known.jpg",
                                now.minus(Duration.ofDays(2)))));
        when(storage.list("avatars/user/"))
                .thenReturn(List.of(new MediaStorage.StoredObject(
                        "avatars/user/known/512.webp",
                        now.minus(Duration.ofDays(2)))));
        when(storage.list("avatars/group/")).thenReturn(List.of());

        new MediaCleanupJob(
                repository,
                storage,
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofHours(24)).cleanOrphanedObjects();

        verify(storage).delete("message-images/orphan/old.jpg");
        verify(storage, never()).delete("message-images/orphan/recent.jpg");
        verify(storage, never()).delete("message-images/orphan/at-cutoff.jpg");
        verify(storage, never()).delete("message-images/known.jpg");
        verify(storage, never()).delete("avatars/user/known/512.webp");
    }

    @Test
    void keeps_scanning_other_prefixes_when_one_prefix_fails() {
        MediaRepository repository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        when(repository.findReferencedObjectKeys()).thenReturn(Set.of());
        when(storage.list("message-images/"))
                .thenThrow(new MediaException(com.jitong.im.platform.error.ApiErrorDefinition.INTERNAL_ERROR));
        when(storage.list("avatars/user/"))
                .thenReturn(List.of(new MediaStorage.StoredObject(
                        "avatars/user/orphan/old.webp",
                        now.minus(Duration.ofDays(2)))));
        when(storage.list("avatars/group/")).thenReturn(List.of());

        new MediaCleanupJob(
                repository,
                storage,
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofHours(24)).cleanOrphanedObjects();

        verify(storage).delete("avatars/user/orphan/old.webp");
    }

    @Test
    void retries_an_orphan_when_deletion_fails() {
        MediaRepository repository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        MediaStorage.StoredObject orphan = new MediaStorage.StoredObject(
                "avatars/group/orphan/old.webp",
                now.minus(Duration.ofDays(2)));
        when(repository.findReferencedObjectKeys()).thenReturn(Set.of());
        when(storage.list("message-images/")).thenReturn(List.of());
        when(storage.list("avatars/user/")).thenReturn(List.of());
        when(storage.list("avatars/group/")).thenReturn(List.of(orphan));
        doThrow(new MediaException(
                com.jitong.im.platform.error.ApiErrorDefinition.INTERNAL_ERROR))
                .doNothing()
                .when(storage)
                .delete("avatars/group/orphan/old.webp");

        MediaCleanupJob job = new MediaCleanupJob(
                repository,
                storage,
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofHours(24));
        job.cleanOrphanedObjects();
        job.cleanOrphanedObjects();

        verify(storage, times(2)).delete("avatars/group/orphan/old.webp");
    }

    @Test
    void skips_orphan_scan_when_database_reference_lookup_fails() {
        MediaRepository repository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        when(repository.findReferencedObjectKeys())
                .thenThrow(new MediaException(
                        com.jitong.im.platform.error.ApiErrorDefinition.INTERNAL_ERROR));

        new MediaCleanupJob(
                repository,
                storage,
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofHours(24)).cleanOrphanedObjects();

        verify(storage, never()).list(org.mockito.ArgumentMatchers.anyString());
        verify(storage, never()).delete(org.mockito.ArgumentMatchers.anyString());
    }
}
