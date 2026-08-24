package com.jitong.im.ai;

import com.jitong.im.media.MediaService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiContextImageLoaderTest {

    @Test
    void loads_at_most_four_authorized_normalized_images_per_task() {
        UUID ownerUserId = UUID.randomUUID();
        MediaService mediaService = mock(MediaService.class);
        List<AiContextMessage> messages = IntStream.range(0, 6)
                .mapToObj(index -> new AiContextMessage(
                        UUID.randomUUID(),
                        index + 1,
                        UUID.randomUUID(),
                        "IMAGE",
                        "[图片]",
                        UUID.randomUUID(),
                        Integer.toHexString(index).repeat(64).substring(0, 64)))
                .toList();
        when(mediaService.loadAiImage(any(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.of(new MediaService.AiImageContent(
                        invocation.getArgument(2),
                        "image/jpeg",
                        1024,
                        512,
                        new byte[]{1, 2, 3})));

        List<AiContextImage> loaded = new AiContextImageLoader(mediaService)
                .load(ownerUserId, messages, true);

        assertThat(loaded).hasSize(4);
        assertThat(loaded).extracting(AiContextImage::messageId)
                .containsExactlyElementsOf(messages.subList(0, 4).stream()
                        .map(AiContextMessage::messageId)
                        .toList());
        verify(mediaService, times(4)).loadAiImage(any(), any(), any(), any());
    }

    @Test
    void disabled_image_input_never_reads_media() {
        MediaService mediaService = mock(MediaService.class);
        AiContextMessage image = new AiContextMessage(
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "IMAGE",
                "[图片]",
                UUID.randomUUID(),
                "a".repeat(64));

        assertThat(new AiContextImageLoader(mediaService)
                .load(UUID.randomUUID(), List.of(image), false)).isEmpty();
        verify(mediaService, never()).loadAiImage(any(), any(), any(), any());
    }
}
