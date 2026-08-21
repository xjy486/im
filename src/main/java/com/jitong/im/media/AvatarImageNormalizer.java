package com.jitong.im.media;

import com.jitong.im.platform.error.ApiErrorDefinition;
import com.luciad.imageio.webp.WebPWriteParam;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

final class AvatarImageNormalizer {

    static final int MAX_INPUT_BYTES = 10 * 1024 * 1024;
    static final int FULL_SIZE = 512;
    static final int THUMBNAIL_SIZE = 96;

    private AvatarImageNormalizer() {
    }

    static NormalizedAvatar normalize(byte[] input, AvatarCrop requestedCrop) {
        if (input == null || input.length == 0) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
        }
        if (input.length > MAX_INPUT_BYTES) {
            throw new MediaException(ApiErrorDefinition.MEDIA_TOO_LARGE);
        }

        BufferedImage decoded = decode(input);
        AvatarCrop crop = requestedCrop == null
                ? centeredSquare(decoded.getWidth(), decoded.getHeight())
                : requestedCrop;
        validateCrop(crop, decoded.getWidth(), decoded.getHeight());
        BufferedImage square = crop(decoded, crop);
        Variant full = encodeWebp(square, FULL_SIZE);
        Variant thumbnail = encodeWebp(square, THUMBNAIL_SIZE);
        return new NormalizedAvatar(crop, full, thumbnail);
    }

    private static BufferedImage decode(byte[] input) {
        try (var imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            if (imageInput == null) {
                throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
            }
            Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
            }
            javax.imageio.ImageReader reader = readers.next();
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
                        || ((long) width * height) > 20_000_000L) {
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
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof MediaException mediaException) {
                throw mediaException;
            }
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID, exception);
        }
    }

    private static AvatarCrop centeredSquare(int width, int height) {
        int size = Math.min(width, height);
        return new AvatarCrop((width - size) / 2, (height - size) / 2, size, size);
    }

    private static void validateCrop(AvatarCrop crop, int imageWidth, int imageHeight) {
        if (crop == null
                || crop.x() < 0
                || crop.y() < 0
                || crop.width() <= 0
                || crop.height() <= 0
                || crop.x() > imageWidth - crop.width()
                || crop.y() > imageHeight - crop.height()) {
            throw new MediaException(ApiErrorDefinition.MEDIA_INVALID);
        }
    }

    private static BufferedImage crop(BufferedImage source, AvatarCrop crop) {
        BufferedImage target = new BufferedImage(
                crop.width(),
                crop.height(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(
                source,
                0,
                0,
                crop.width(),
                crop.height(),
                crop.x(),
                crop.y(),
                crop.x() + crop.width(),
                crop.y() + crop.height(),
                null);
        graphics.dispose();
        return target;
    }

    private static Variant encodeWebp(BufferedImage source, int size) {
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, size, size);
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, size, size, null);
        graphics.dispose();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("webp");
        if (!writers.hasNext()) {
            throw new MediaException(ApiErrorDefinition.INTERNAL_ERROR);
        }
        ImageWriter writer = writers.next();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(imageOutput);
                ImageWriteParam writeParam = writer.getDefaultWriteParam();
                if (writeParam instanceof WebPWriteParam webpWriteParam) {
                    webpWriteParam.setCompressionQuality(0.9f);
                }
                writer.write(null, new IIOImage(target, null, null), writeParam);
            }
            return new Variant(output.toByteArray(), size, size, "image/webp");
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof MediaException mediaException) {
                throw mediaException;
            }
            throw new MediaException(ApiErrorDefinition.INTERNAL_ERROR, exception);
        } finally {
            writer.dispose();
        }
    }

    record NormalizedAvatar(
            AvatarCrop crop,
            Variant full,
            Variant thumbnail
    ) {
    }

    record Variant(
            byte[] bytes,
            int width,
            int height,
            String contentType
    ) {
    }
}
