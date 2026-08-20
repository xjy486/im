package com.jitong.im.media;

import com.jitong.im.platform.error.ApiErrorDefinition;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;

final class ImageNormalizer {

    static final int MAX_INPUT_BYTES = 10 * 1024 * 1024;
    static final int MAX_OUTPUT_BYTES = 5 * 1024 * 1024;
    static final int MAX_LONG_EDGE = 2048;
    static final int THUMBNAIL_LONG_EDGE = 320;
    static final long MAX_DECODE_PIXELS = 20_000_000L;

    private ImageNormalizer() {
    }

    static NormalizedImage normalize(byte[] input) {
        if (input == null || input.length == 0) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
        }
        if (input.length > MAX_INPUT_BYTES) {
            throw new MediaException(ApiErrorDefinition.MEDIA_TOO_LARGE);
        }

        BufferedImage decoded = decode(input);
        BufferedImage normalized = scale(decoded, MAX_LONG_EDGE);
        byte[] original = encodeJpeg(normalized);
        if (original.length > MAX_OUTPUT_BYTES) {
            throw new MediaException(ApiErrorDefinition.MEDIA_TOO_LARGE);
        }
        byte[] thumbnail = encodeJpeg(scale(decoded, THUMBNAIL_LONG_EDGE));
        return new NormalizedImage(
                original,
                thumbnail,
                normalized.getWidth(),
                normalized.getHeight(),
                "image/jpeg",
                sha256(original));
    }

    private static BufferedImage decode(byte[] input) {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(
                new ByteArrayInputStream(input))) {
            if (imageInput == null) {
                throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("jpg")
                        && !format.equals("png") && !format.equals("gif")) {
                    throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0
                        || ((long) width * height) > MAX_DECODE_PIXELS) {
                    throw new MediaException(ApiErrorDefinition.MEDIA_DIMENSIONS_TOO_LARGE);
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
                }
                return decoded;
            } finally {
                reader.dispose();
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID, exception);
        }
    }

    private static BufferedImage scale(BufferedImage source, int maxLongEdge) {
        double scale = Math.min(1d, maxLongEdge / (double) Math.max(
                source.getWidth(),
                source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "jpg", output)) {
                throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID, exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    record NormalizedImage(
            byte[] original,
            byte[] thumbnail,
            int width,
            int height,
            String contentType,
            String sha256
    ) {
    }
}
