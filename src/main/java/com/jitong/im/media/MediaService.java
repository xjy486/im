package com.jitong.im.media;

import com.jitong.im.auth.UuidV7;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;

@Service
public class MediaService {

    private final MediaRepository repository;
    private final MediaStorage storage;
    private final Clock clock;

    @Autowired
    public MediaService(MediaRepository repository, MediaStorage storage) {
        this(repository, storage, Clock.systemUTC());
    }

    MediaService(MediaRepository repository, MediaStorage storage, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public MediaUploadResponse uploadMessageImage(
            UUID uploaderId,
            UUID uploadId,
            MultipartFile file
    ) {
        if (uploadId == null || uploadId.version() != 4 || file == null || file.isEmpty()) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
        }
        ImageNormalizer.NormalizedImage image;
        try {
            image = ImageNormalizer.normalize(file.getBytes());
        } catch (IOException exception) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID, exception);
        }

        MediaRecord existing = repository.findByUploadId(uploaderId, uploadId);
        if (existing != null) {
            if (!existing.sha256().equals(image.sha256())) {
                throw new MediaException(ApiErrorDefinition.IDEMPOTENCY_CONFLICT);
            }
            return response(existing);
        }

        UUID mediaId = UuidV7.random();
        String prefix = "message-images/" + mediaId;
        MediaRecord record = repository.insertTemp(
                mediaId,
                uploaderId,
                uploadId,
                prefix + "/original.jpg",
                prefix + "/thumbnail.jpg",
                image.contentType(),
                image.width(),
                image.height(),
                image.original().length,
                image.sha256(),
                clock.instant());
        try {
            storage.put(record.originalObjectKey(), image.original(), image.contentType());
            storage.put(record.thumbnailObjectKey(), image.thumbnail(), image.contentType());
        } catch (RuntimeException exception) {
            try {
                storage.delete(record.originalObjectKey());
            } catch (RuntimeException ignored) {
                // Best effort cleanup; the media row remains TEMP for the cleanup job.
            }
            try {
                storage.delete(record.thumbnailObjectKey());
            } catch (RuntimeException ignored) {
                // Best effort cleanup; the media row remains TEMP for the cleanup job.
            }
            throw exception;
        }
        return response(record);
    }

    @Transactional
    public void bindMessageImage(UUID uploaderId, UUID mediaId, UUID messageId) {
        MediaRecord media = repository.findByIdForUpdate(mediaId);
        if (media == null) {
            throw new MediaException(ApiErrorDefinition.MEDIA_NOT_FOUND);
        }
        if (!media.uploaderId().equals(uploaderId)) {
            throw new MediaException(ApiErrorDefinition.MEDIA_FORBIDDEN);
        }
        if (!"MESSAGE_IMAGE".equals(media.purpose())) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
        }
        if (!media.createdAt().plus(Duration.ofHours(24)).isAfter(clock.instant())) {
            throw new MediaException(ApiErrorDefinition.MEDIA_EXPIRED);
        }
        if ("EXPIRED".equals(media.state())) {
            throw new MediaException(ApiErrorDefinition.MEDIA_EXPIRED);
        }
        if ("BOUND".equals(media.state())) {
            if (messageId.equals(media.attachedMessageId())) {
                return;
            }
            throw new MediaException(ApiErrorDefinition.IDEMPOTENCY_CONFLICT);
        }
        if (!repository.bindToMessage(mediaId, uploaderId, messageId, clock.instant())) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
        }
    }

    @Transactional(readOnly = true)
    public MediaDownload download(UUID userId, UUID mediaId, String variant) {
        if (!"full".equals(variant) && !"thumb".equals(variant)) {
            throw new MediaException(ApiErrorDefinition.INVALID_REQUEST);
        }
        MediaRepository.AccessRecord access = repository.findAccess(mediaId, userId);
        if (access == null) {
            throw new MediaException(ApiErrorDefinition.MEDIA_NOT_FOUND);
        }
        if (access.expired()) {
            throw new MediaException(ApiErrorDefinition.MEDIA_EXPIRED);
        }
        if (!access.permitted()) {
            throw new MediaException(ApiErrorDefinition.MEDIA_FORBIDDEN);
        }
        String objectKey = "thumb".equals(variant)
                ? access.thumbnailObjectKey()
                : access.originalObjectKey();
        MediaStorage.StoredMedia content = storage.get(objectKey);
        return new MediaDownload(content.content(), content.contentLength(), access.contentType());
    }

    @Transactional
    public void expireBoundMedia(UUID messageId) {
        repository.expireMediaForMessage(messageId, clock.instant());
    }

    @Transactional(readOnly = true)
    public Optional<AiImageContent> loadAiImage(
            UUID userId,
            UUID messageId,
            UUID mediaId,
            String expectedSha256
    ) {
        if (userId == null || messageId == null || mediaId == null || expectedSha256 == null) {
            return Optional.empty();
        }
        MediaRepository.AiAccessRecord access = repository.findAiAccess(
                mediaId,
                messageId,
                userId,
                expectedSha256);
        if (access == null
                || access.byteSize() < 1
                || access.byteSize() > ImageNormalizer.MAX_OUTPUT_BYTES) {
            return Optional.empty();
        }
        try {
            MediaStorage.StoredMedia stored = storage.get(access.originalObjectKey());
            byte[] bytes;
            try (var input = stored.content()) {
                if (stored.contentLength() != access.byteSize()
                        || stored.contentLength() > ImageNormalizer.MAX_OUTPUT_BYTES) {
                    return Optional.empty();
                }
                bytes = input.readNBytes(ImageNormalizer.MAX_OUTPUT_BYTES + 1);
            }
            if (bytes.length != access.byteSize()
                    || !access.sha256().equals(ImageNormalizer.sha256(bytes))) {
                return Optional.empty();
            }
            ImageNormalizer.NormalizedAiImage image = ImageNormalizer.normalizeForAi(bytes);
            return Optional.of(new AiImageContent(
                    access.mediaId(),
                    image.contentType(),
                    image.width(),
                    image.height(),
                    image.content()));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    MediaUploadResponse response(MediaRecord record) {
        return new MediaUploadResponse(
                1,
                record.mediaId(),
                record.purpose(),
                record.state(),
                record.contentType(),
                record.width(),
                record.height(),
                record.byteSize(),
                record.sha256());
    }

    public record AiImageContent(
            UUID mediaId,
            String contentType,
            int width,
            int height,
            byte[] content
    ) {

        public AiImageContent {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
