package com.jitong.im.media;

import com.jitong.im.platform.error.ApiErrorDefinition;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarImageNormalizerTest {

    @Test
    void crops_the_requested_region_and_writes_independent_webp_variants() throws Exception {
        BufferedImage source = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < source.getWidth(); x++) {
            for (int y = 0; y < source.getHeight(); y++) {
                source.setRGB(x, y, new Color(x % 255, y % 255, 90).getRGB());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);

        AvatarImageNormalizer.NormalizedAvatar avatar = AvatarImageNormalizer.normalize(
                encoded.toByteArray(),
                new AvatarCrop(100, 0, 200, 200));

        assertThat(avatar.full().contentType()).isEqualTo("image/webp");
        assertThat(avatar.full().width()).isEqualTo(512);
        assertThat(avatar.full().height()).isEqualTo(512);
        assertThat(avatar.thumbnail().width()).isEqualTo(96);
        assertThat(avatar.thumbnail().height()).isEqualTo(96);
        assertThat(avatar.full().bytes()).isNotEmpty();
        assertThat(avatar.thumbnail().bytes()).isNotEmpty();
        assertThat(new String(avatar.full().bytes(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("RIFF");
        assertThat(ImageIO.read(new ByteArrayInputStream(avatar.full().bytes()))).isNotNull();
    }

    @Test
    void rejects_a_crop_that_is_outside_the_decoded_image() throws Exception {
        BufferedImage source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);

        assertThatThrownBy(() -> AvatarImageNormalizer.normalize(
                encoded.toByteArray(),
                new AvatarCrop(50, 50, 60, 60)))
                .isInstanceOf(MediaException.class)
                .extracting(exception -> ((MediaException) exception).definition())
                .isEqualTo(ApiErrorDefinition.MEDIA_INVALID);
    }

    @Test
    void uses_a_centered_square_when_no_crop_is_supplied() throws Exception {
        BufferedImage source = new BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);

        AvatarImageNormalizer.NormalizedAvatar avatar = AvatarImageNormalizer.normalize(
                encoded.toByteArray(),
                null);

        assertThat(avatar.crop()).isEqualTo(new AvatarCrop(100, 0, 100, 100));
    }
}
