package com.jitong.im.media;

import com.jitong.im.auth.UuidV7;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class AvatarService {

    private final MediaRepository mediaRepository;
    private final MediaStorage mediaStorage;
    private final AvatarRepository avatarRepository;
    private final SyncService syncService;
    private final Clock clock;

    @Autowired
    AvatarService(
            MediaRepository mediaRepository,
            MediaStorage mediaStorage,
            AvatarRepository avatarRepository,
            SyncService syncService
    ) {
        this(mediaRepository, mediaStorage, avatarRepository, syncService, Clock.systemUTC());
    }

    AvatarService(
            MediaRepository mediaRepository,
            MediaStorage mediaStorage,
            AvatarRepository avatarRepository,
            SyncService syncService,
            Clock clock
    ) {
        this.mediaRepository = mediaRepository;
        this.mediaStorage = mediaStorage;
        this.avatarRepository = avatarRepository;
        this.syncService = syncService;
        this.clock = clock;
    }

    @Transactional
    public AvatarUploadResponse replaceUserAvatar(
            UUID userId,
            UUID uploadId,
            MultipartFile file,
            AvatarCrop crop
    ) {
        if (uploadId == null || uploadId.version() != 4 || file == null || file.isEmpty()) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
        }
        AvatarImageNormalizer.NormalizedAvatar avatar;
        try {
            avatar = AvatarImageNormalizer.normalize(file.getBytes(), crop);
        } catch (IOException exception) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID, exception);
        }

        AvatarRepository.AvatarOwner owner = avatarRepository.findUserForUpdate(userId);
        if (owner == null) {
            throw new MediaException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        AvatarRepository.AvatarMedia existingUpload =
                avatarRepository.findAvatarUpload(userId, uploadId);
        if (existingUpload != null) {
            if ("EXPIRED".equals(existingUpload.state())) {
                throw new MediaException(ApiErrorDefinition.MEDIA_EXPIRED);
            }
            if (!existingUpload.sha256().equals(sha256(avatar.full().bytes()))) {
                throw new MediaException(ApiErrorDefinition.IDEMPOTENCY_CONFLICT);
            }
            return new AvatarUploadResponse(
                    1,
                    existingUpload.mediaId(),
                    "AVATAR",
                    existingUpload.state(),
                    existingUpload.contentType(),
                    existingUpload.width(),
                    existingUpload.height(),
                    existingUpload.byteSize(),
                    owner.avatarVersion(),
                    avatarUrl(userId, owner.avatarVersion()));
        }

        UUID mediaId = UuidV7.random();
        String prefix = "avatars/user/" + userId + "/" + mediaId;
        Instant now = clock.instant();
        MediaRecord media = mediaRepository.insertTemp(
                mediaId,
                "AVATAR",
                userId,
                uploadId,
                prefix + "/512.webp",
                prefix + "/96.webp",
                "image/webp",
                AvatarImageNormalizer.FULL_SIZE,
                AvatarImageNormalizer.FULL_SIZE,
                avatar.full().bytes().length,
                sha256(avatar.full().bytes()),
                null,
                null,
                now);
        registerRollbackCleanup(media);
        try {
            mediaStorage.put(
                    media.originalObjectKey(),
                    avatar.full().bytes(),
                    avatar.full().contentType());
            mediaStorage.put(
                    media.thumbnailObjectKey(),
                    avatar.thumbnail().bytes(),
                    avatar.thumbnail().contentType());
            if (!mediaRepository.bindToAvatar(mediaId, userId, "USER", userId, now)) {
                throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
            }
            UUID previousMediaId = owner.avatarMediaId();
            long nextVersion = owner.avatarVersion() + 1;
            avatarRepository.replaceUserAvatar(userId, mediaId, nextVersion, now);
            if (previousMediaId != null) {
                mediaRepository.expireAvatar(previousMediaId, "USER", userId, now);
            }
            syncService.recordEventForUsers(
                    avatarRepository.profileEventRecipients(userId),
                    "USER_PROFILE_UPDATED",
                    userId,
                    null);
            return new AvatarUploadResponse(
                    1,
                    mediaId,
                    "AVATAR",
                    "BOUND",
                    "image/webp",
                    AvatarImageNormalizer.FULL_SIZE,
                    AvatarImageNormalizer.FULL_SIZE,
                    avatar.full().bytes().length,
                    nextVersion,
                    avatarUrl(userId, nextVersion));
        } catch (RuntimeException exception) {
            try {
                mediaStorage.delete(media.originalObjectKey());
            } catch (RuntimeException ignored) {
                // Cleanup job retries the expired media row.
            }
            try {
                mediaStorage.delete(media.thumbnailObjectKey());
            } catch (RuntimeException ignored) {
                // Cleanup job retries the expired media row.
            }
            throw exception;
        }
    }

    @Transactional
    public void removeUserAvatar(UUID userId) {
        AvatarRepository.AvatarOwner owner = avatarRepository.findUserForUpdate(userId);
        if (owner == null) {
            throw new MediaException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        if (owner.avatarMediaId() == null) {
            return;
        }
        Instant now = clock.instant();
        avatarRepository.removeUserAvatar(userId, owner.avatarVersion() + 1);
        mediaRepository.expireAvatar(owner.avatarMediaId(), "USER", userId, now);
        syncService.recordEventForUsers(
                avatarRepository.profileEventRecipients(userId),
                "USER_PROFILE_UPDATED",
                userId,
                null);
    }

    @Transactional
    public AvatarUploadResponse replaceGroupAvatar(
            UUID ownerUserId,
            UUID conversationId,
            UUID uploadId,
            MultipartFile file,
            AvatarCrop crop
    ) {
        AvatarRepository.GroupOwner group =
                avatarRepository.findGroupForUpdate(conversationId, ownerUserId);
        if (group == null) {
            throw new MediaException(ApiErrorDefinition.FORBIDDEN);
        }
        AvatarImageNormalizer.NormalizedAvatar avatar = normalize(file, uploadId, crop);
        UUID mediaId = UuidV7.random();
        String prefix = "avatars/group/" + conversationId + "/" + mediaId;
        Instant now = clock.instant();
        MediaRecord media = mediaRepository.insertTemp(
                mediaId, "AVATAR", ownerUserId, uploadId,
                prefix + "/512.webp", prefix + "/96.webp", "image/webp",
                AvatarImageNormalizer.FULL_SIZE, AvatarImageNormalizer.FULL_SIZE,
                avatar.full().bytes().length, sha256(avatar.full().bytes()),
                null, null, now);
        registerRollbackCleanup(media);
        try {
            mediaStorage.put(media.originalObjectKey(), avatar.full().bytes(), "image/webp");
            mediaStorage.put(media.thumbnailObjectKey(), avatar.thumbnail().bytes(), "image/webp");
            if (!mediaRepository.bindToAvatar(mediaId, ownerUserId, "GROUP", conversationId, now)) {
                throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
            }
            long nextVersion = group.avatarVersion() + 1;
            avatarRepository.replaceGroupAvatar(conversationId, mediaId, nextVersion);
            if (group.avatarMediaId() != null) {
                mediaRepository.expireAvatar(group.avatarMediaId(), "GROUP", conversationId, now);
            }
            syncService.recordEventForUsers(
                    avatarRepository.activeGroupMemberIds(conversationId),
                    "GROUP_PROFILE_UPDATED",
                    conversationId,
                    conversationId);
            return new AvatarUploadResponse(
                    1, mediaId, "AVATAR", "BOUND", "image/webp",
                    AvatarImageNormalizer.FULL_SIZE, AvatarImageNormalizer.FULL_SIZE,
                    avatar.full().bytes().length, nextVersion,
                    "/api/v1/groups/" + conversationId + "/avatar?variant=thumb&avatarVersion=" + nextVersion);
        } catch (RuntimeException exception) {
            safeDelete(media.originalObjectKey());
            safeDelete(media.thumbnailObjectKey());
            throw exception;
        }
    }

    @Transactional
    public void removeGroupAvatar(UUID ownerUserId, UUID conversationId) {
        AvatarRepository.GroupOwner group =
                avatarRepository.findGroupForUpdate(conversationId, ownerUserId);
        if (group == null) {
            throw new MediaException(ApiErrorDefinition.FORBIDDEN);
        }
        if (group.avatarMediaId() == null) {
            return;
        }
        Instant now = clock.instant();
        avatarRepository.removeGroupAvatar(conversationId, group.avatarVersion() + 1);
        mediaRepository.expireAvatar(group.avatarMediaId(), "GROUP", conversationId, now);
        syncService.recordEventForUsers(
                avatarRepository.activeGroupMemberIds(conversationId),
                "GROUP_PROFILE_UPDATED",
                conversationId,
                conversationId);
    }

    @Transactional(readOnly = true)
    public MediaDownload downloadGroupAvatar(
            UUID requesterId,
            UUID conversationId,
            String variant,
            Long requestedAvatarVersion
    ) {
        if (!"full".equals(variant) && !"thumb".equals(variant)) {
            throw new MediaException(ApiErrorDefinition.INVALID_REQUEST);
        }
        if (!avatarRepository.activeGroupMemberIds(conversationId).contains(requesterId)
                && !avatarRepository.isDiscoverableGroup(conversationId)) {
            throw new MediaException(ApiErrorDefinition.MEDIA_FORBIDDEN);
        }
        AvatarRepository.GroupProfile profile = avatarRepository.findGroupProfile(conversationId);
        if (profile == null) {
            throw new MediaException(ApiErrorDefinition.MEDIA_NOT_FOUND);
        }
        if (requestedAvatarVersion != null
                && requestedAvatarVersion.longValue() != profile.avatarVersion()) {
            throw new MediaException(ApiErrorDefinition.MEDIA_EXPIRED);
        }
        if (profile.avatarMediaId() == null) {
            throw new MediaException(ApiErrorDefinition.MEDIA_NOT_FOUND);
        }
        MediaRecord avatar = mediaRepository.findById(profile.avatarMediaId());
        if (avatar == null || !"BOUND".equals(avatar.state())) {
            throw new MediaException(ApiErrorDefinition.MEDIA_NOT_FOUND);
        }
        MediaStorage.StoredMedia stored = mediaStorage.get(
                "thumb".equals(variant) ? avatar.thumbnailObjectKey() : avatar.originalObjectKey());
        return new MediaDownload(stored.content(), stored.contentLength(), stored.contentType());
    }

    @Transactional(readOnly = true)
    public GroupProfile groupProfile(UUID conversationId) {
        AvatarRepository.GroupProfile profile = avatarRepository.findGroupProfile(conversationId);
        if (profile == null) {
            return null;
        }
        return new GroupProfile(
                conversationId,
                profile.avatarMediaId() == null
                        ? null
                        : "/api/v1/groups/" + conversationId
                        + "/avatar?variant=thumb&avatarVersion=" + profile.avatarVersion(),
                profile.avatarVersion());
    }

    @Transactional(readOnly = true)
    public GroupProfile visibleGroupProfile(UUID requesterId, UUID conversationId) {
        if (!avatarRepository.isActiveGroupMember(conversationId, requesterId)) {
            throw new MediaException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        GroupProfile profile = groupProfile(conversationId);
        if (profile == null) {
            throw new MediaException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        return profile;
    }

    @Transactional(readOnly = true)
    public MediaDownload downloadUserAvatar(
            UUID requesterId,
            UUID userId,
            String variant,
            Long requestedAvatarVersion
    ) {
        if (!"full".equals(variant) && !"thumb".equals(variant)) {
            throw new MediaException(ApiErrorDefinition.INVALID_REQUEST);
        }
        if (!requesterId.equals(userId) && !avatarRepository.hasC2cAccess(requesterId, userId)) {
            throw new MediaException(ApiErrorDefinition.MEDIA_FORBIDDEN);
        }
        AvatarRepository.UserProfile profile = avatarRepository.findUserProfile(userId);
        if (profile == null) {
            throw new MediaException(ApiErrorDefinition.MEDIA_NOT_FOUND);
        }
        if (requestedAvatarVersion != null
                && requestedAvatarVersion.longValue() != profile.avatarVersion()) {
            throw new MediaException(ApiErrorDefinition.MEDIA_EXPIRED);
        }
        AvatarRepository.AvatarMedia avatar = avatarRepository.findCurrentUserAvatar(userId);
        if (avatar == null) {
            throw new MediaException(ApiErrorDefinition.MEDIA_NOT_FOUND);
        }
        String key = "thumb".equals(variant)
                ? avatar.thumbnailObjectKey()
                : avatar.originalObjectKey();
        MediaStorage.StoredMedia stored = mediaStorage.get(key);
        return new MediaDownload(stored.content(), stored.contentLength(), avatar.contentType());
    }

    @Transactional(readOnly = true)
    public UserProfile visibleProfile(UUID requesterId, UUID userId) {
        if (!requesterId.equals(userId) && !avatarRepository.hasC2cAccess(requesterId, userId)) {
            throw new MediaException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        UserProfile profile = profile(userId);
        if (profile == null) {
            throw new MediaException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        return profile;
    }

    @Transactional(readOnly = true)
    public UserProfile profile(UUID userId) {
        AvatarRepository.UserProfile profile = avatarRepository.findUserProfile(userId);
        if (profile == null) {
            return null;
        }
        return new UserProfile(
                profile.userId(),
                profile.displayName(),
                profile.avatarMediaId() == null
                        ? null
                        : avatarUrl(profile.userId(), profile.avatarVersion()),
                profile.avatarVersion(),
                fallback(profile.displayName()));
    }

    public record UserProfile(
            UUID userId,
            String displayName,
            String avatarUrl,
            long avatarVersion,
            String avatarFallback
    ) {
    }

    public record GroupProfile(
            UUID conversationId,
            String avatarUrl,
            long avatarVersion
    ) {
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AvatarImageNormalizer.NormalizedAvatar normalize(
            MultipartFile file,
            UUID uploadId,
            AvatarCrop crop
    ) {
        if (uploadId == null || uploadId.version() != 4 || file == null || file.isEmpty()) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
        }
        try {
            return AvatarImageNormalizer.normalize(file.getBytes(), crop);
        } catch (IOException exception) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID, exception);
        }
    }

    private void safeDelete(String objectKey) {
        try {
            mediaStorage.delete(objectKey);
        } catch (RuntimeException ignored) {
            // Cleanup retries EXPIRED rows.
        }
    }

    private void registerRollbackCleanup(MediaRecord media) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    safeDelete(media.originalObjectKey());
                    safeDelete(media.thumbnailObjectKey());
                }
            }
        });
    }

    private String avatarUrl(UUID userId, long version) {
        return "/api/v1/users/" + userId
                + "/avatar?variant=thumb&avatarVersion=" + version;
    }

    private String fallback(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "?";
        }
        return new String(Character.toChars(displayName.codePointAt(0)));
    }

}
