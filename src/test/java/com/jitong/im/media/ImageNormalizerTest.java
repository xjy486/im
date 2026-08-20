package com.jitong.im.media;

import com.jitong.im.platform.error.ApiErrorDefinition;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageNormalizerTest {

    @Test
    void decodes_real_image_reencodes_as_jpeg_and_caps_long_edge() throws Exception {
        BufferedImage source = new BufferedImage(4096, 1024, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < source.getWidth(); x++) {
            for (int y = 0; y < source.getHeight(); y++) {
                source.setRGB(x, y, new Color(x % 255, y % 255, 80, 120).getRGB());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);

        ImageNormalizer.NormalizedImage normalized = ImageNormalizer.normalize(encoded.toByteArray());

        assertThat(normalized.contentType()).isEqualTo("image/jpeg");
        assertThat(Math.max(normalized.width(), normalized.height()))
                .isEqualTo(ImageNormalizer.MAX_LONG_EDGE);
        assertThat(normalized.original()).isNotEmpty();
        assertThat(normalized.thumbnail()).isNotEmpty();
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(normalized.original()))).isNotNull();
    }

    @Test
    void rejects_bytes_that_are_not_a_safe_supported_image() {
        assertThatThrownBy(() -> ImageNormalizer.normalize("pretend image".getBytes()))
                .isInstanceOf(MediaException.class)
                .extracting(exception -> ((MediaException) exception).definition())
                .isEqualTo(ApiErrorDefinition.MEDIA_INVALID);
    }
}
