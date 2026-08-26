package com.jitong.im.media;

import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvatarServiceTest {

    @Test
    void replacing_an_avatar_advances_the_version_expires_the_old_media_and_emits_profile_events()
            throws Exception {
        MediaRepository mediaRepository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        AvatarRepository avatarRepository = mock(AvatarRepository.class);
        SyncService syncService = mock(SyncService.class);
        UUID userId = UUID.randomUUID();
        UUID oldMediaId = UUID.randomUUID();
        when(avatarRepository.findUserForUpdate(userId)).thenReturn(
                new AvatarRepository.AvatarOwner(userId, oldMediaId, 4));
        when(avatarRepository.findAvatarUpload(eq(userId), any())).thenReturn(null);
        when(mediaRepository.insertTemp(
                any(), eq("AVATAR"), eq(userId), any(), any(), any(), eq("image/webp"),
                eq(512), eq(512), any(Long.class), any(), isNull(), isNull(), any()))
                .thenAnswer(invocation -> new MediaRecord(
                        invocation.getArgument(0),
                        "AVATAR",
                        userId,
                        invocation.getArgument(3),
                        "TEMP",
                        invocation.getArgument(4),
                        invocation.getArgument(5),
                        "image/webp",
                        512,
                        512,
                        ((Number) invocation.getArgument(8)).longValue(),
                        (String) invocation.getArgument(10),
                        null,
                        Instant.parse("2026-08-21T00:00:00Z"),
                        null,
                        null,
                        null,
                        userId,
                        "USER"));
        when(mediaRepository.bindToAvatar(any(), eq(userId), eq("USER"), eq(userId), any()))
                .thenReturn(true);
        when(avatarRepository.profileEventRecipients(userId)).thenReturn(java.util.List.of(userId));

        AvatarService service = new AvatarService(
                mediaRepository,
                storage,
                avatarRepository,
                syncService,
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        AvatarUploadResponse response = service.replaceUserAvatar(
                userId,
                UUID.randomUUID(),
                new MockMultipartFile("file", "avatar.png", "image/png", png()),
                null);

        assertThat(response.avatarVersion()).isEqualTo(5);
        assertThat(response.state()).isEqualTo("BOUND");
        verify(mediaRepository).expireAvatar(
                eq(oldMediaId), eq("USER"), eq(userId), any());
        verify(syncService).recordEventForUsers(
                eq(java.util.List.of(userId)),
                eq("USER_PROFILE_UPDATED"),
                eq(userId),
                eq(null));
    }

    @Test
    void an_avatar_is_not_readable_by_a_user_without_an_active_c2c_conversation() {
        MediaRepository mediaRepository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        AvatarRepository avatarRepository = mock(AvatarRepository.class);
        SyncService syncService = mock(SyncService.class);
        UUID ownerId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        when(avatarRepository.hasC2cAccess(requesterId, ownerId)).thenReturn(false);

        AvatarService service = new AvatarService(
                mediaRepository,
                storage,
                avatarRepository,
                syncService,
                Clock.systemUTC());

        assertThatThrownBy(() -> service.downloadUserAvatar(
                requesterId,
                ownerId,
                "thumb",
                null))
                .isInstanceOf(MediaException.class)
                .extracting(exception -> ((MediaException) exception).definition())
                .isEqualTo(ApiErrorDefinition.MEDIA_FORBIDDEN);
    }

    @Test
    void updating_a_display_name_persists_it_and_emits_a_profile_event() {
        MediaRepository mediaRepository = mock(MediaRepository.class);
        MediaStorage storage = mock(MediaStorage.class);
        AvatarRepository avatarRepository = mock(AvatarRepository.class);
        SyncService syncService = mock(SyncService.class);
        UUID userId = UUID.randomUUID();
        when(avatarRepository.findUserForUpdate(userId)).thenReturn(
                new AvatarRepository.AvatarOwner(userId, "Old name", null, 0));
        when(avatarRepository.profileEventRecipients(userId)).thenReturn(java.util.List.of(userId));
        when(avatarRepository.findUserProfile(userId)).thenReturn(
                new AvatarRepository.UserProfile(userId, "New name", null, 0));

        AvatarService service = new AvatarService(
                mediaRepository,
                storage,
                avatarRepository,
                syncService,
                Clock.systemUTC());

        AvatarService.UserProfile profile = service.updateUserProfile(userId, "  New name  ");

        assertThat(profile.displayName()).isEqualTo("New name");
        verify(avatarRepository).updateUserDisplayName(userId, "New name");
        verify(syncService).recordEventForUsers(
                eq(java.util.List.of(userId)),
                eq("USER_PROFILE_UPDATED"),
                eq(userId),
                eq(null));
    }

    @Test
    void updating_a_display_name_rejects_blank_values() {
        AvatarRepository avatarRepository = mock(AvatarRepository.class);
        AvatarService service = new AvatarService(
                mock(MediaRepository.class),
                mock(MediaStorage.class),
                avatarRepository,
                mock(SyncService.class),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.updateUserProfile(UUID.randomUUID(), "   "))
                .isInstanceOf(MediaException.class)
                .extracting(exception -> ((MediaException) exception).definition())
                .isEqualTo(ApiErrorDefinition.INVALID_REQUEST);
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
