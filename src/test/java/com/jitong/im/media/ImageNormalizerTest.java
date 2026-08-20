package com.jitong.im.media;

import com.jitong.im.platform.error.ApiErrorDefinition;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

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

    @Test
    void rejects_input_that_exceeds_the_upload_limit_before_decoding() {
        assertThatThrownBy(() -> ImageNormalizer.normalize(
                new byte[ImageNormalizer.MAX_INPUT_BYTES + 1]))
                .isInstanceOf(MediaException.class)
                .extracting(exception -> ((MediaException) exception).definition())
                .isEqualTo(ApiErrorDefinition.MEDIA_TOO_LARGE);
    }

    @Test
    void rejects_images_that_exceed_the_decode_pixel_limit() throws Exception {
        BufferedImage source = new BufferedImage(5000, 5000, BufferedImage.TYPE_BYTE_BINARY);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);

        assertThatThrownBy(() -> ImageNormalizer.normalize(encoded.toByteArray()))
                .isInstanceOf(MediaException.class)
                .extracting(exception -> ((MediaException) exception).definition())
                .isEqualTo(ApiErrorDefinition.MEDIA_DIMENSIONS_TOO_LARGE);
    }

    @Test
    void removes_exif_and_gps_metadata_when_reencoding_jpeg() throws Exception {
        BufferedImage source = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(source, "jpg", jpeg);
        byte[] metadata = "Exif\0\0GPSLatitude\0GPSLongitude\0".getBytes(StandardCharsets.ISO_8859_1);
        int app1Length = metadata.length + 2;
        ByteArrayOutputStream withExif = new ByteArrayOutputStream();
        withExif.write(0xFF);
        withExif.write(0xD8);
        withExif.write(0xFF);
        withExif.write(0xE1);
        withExif.write((app1Length >>> 8) & 0xFF);
        withExif.write(app1Length & 0xFF);
        withExif.write(metadata);
        withExif.write(jpeg.toByteArray(), 2, jpeg.size() - 2);

        ImageNormalizer.NormalizedImage normalized = ImageNormalizer.normalize(withExif.toByteArray());
        String normalizedBytes = new String(normalized.original(), StandardCharsets.ISO_8859_1);

        assertThat(normalizedBytes).doesNotContain("Exif", "GPSLatitude", "GPSLongitude");
    }
}
