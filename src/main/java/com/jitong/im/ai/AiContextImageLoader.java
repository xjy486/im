package com.jitong.im.ai;

import com.jitong.im.media.MediaService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
class AiContextImageLoader {

    static final int MAX_IMAGES_PER_TASK = 4;

    private final MediaService mediaService;

    AiContextImageLoader(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    List<AiContextImage> load(
            UUID ownerUserId,
            List<AiContextMessage> messages,
            boolean enabled
    ) {
        if (!enabled) {
            return List.of();
        }
        List<AiContextImage> images = new ArrayList<>(MAX_IMAGES_PER_TASK);
        for (AiContextMessage message : messages) {
            if (!message.hasAuthorizedImageReference()) {
                continue;
            }
            mediaService.loadAiImage(
                            ownerUserId,
                            message.messageId(),
                            message.mediaId(),
                            message.mediaSha256())
                    .ifPresent(image -> images.add(new AiContextImage(
                            message.messageId(),
                            image.mediaId(),
                            image.contentType(),
                            image.width(),
                            image.height(),
                            image.content())));
            if (images.size() == MAX_IMAGES_PER_TASK) {
                break;
            }
        }
        return List.copyOf(images);
    }
}
