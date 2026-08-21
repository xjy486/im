package com.jitong.im.desktop.media

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.ImageInputStream

internal object ImageNormalizer {
    const val MAX_INPUT_BYTES = 10 * 1024 * 1024
    const val MAX_OUTPUT_BYTES = 5 * 1024 * 1024
    const val MAX_LONG_EDGE = 2048
    private const val MAX_DECODE_PIXELS = 20_000_000L

    fun normalize(source: ByteArray): ByteArray {
        require(source.isNotEmpty() && source.size <= MAX_INPUT_BYTES) {
            "Image input is too large"
        }
        val decoded = decodeSafely(source)
        val normalized = scale(decoded)
        return try {
            encodeJpeg(normalized)
        } finally {
            normalized.flush()
            decoded.flush()
        }
    }

    private fun decodeSafely(source: ByteArray): BufferedImage {
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(source)).use { input ->
                requireNotNull(input) { "Image cannot be decoded" }
                val readers = ImageIO.getImageReaders(input)
                require(readers.hasNext()) { "Image cannot be decoded" }
                val reader = readers.next()
                try {
                    reader.setInput(input, true, true)
                    val format = reader.formatName.lowercase()
                    require(format in setOf("jpeg", "jpg", "png", "gif")) {
                        "Unsupported image format"
                    }
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    require(
                        width > 0
                            && height > 0
                            && width.toLong() * height <= MAX_DECODE_PIXELS) {
                        "Image dimensions are too large"
                    }
                    return reader.read(0)
                        ?: throw IllegalArgumentException("Image cannot be decoded")
                } finally {
                    reader.dispose()
                }
            }
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalArgumentException("Image cannot be decoded", exception)
        }
    }

    private fun scale(source: BufferedImage): BufferedImage {
        val scale = minOf(
            1.0,
            MAX_LONG_EDGE.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { target ->
            val graphics = target.createGraphics()
            graphics.composite = AlphaComposite.Src
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
            graphics.dispose()
        }
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        var quality = 0.9f
        while (quality >= 0.4f) {
            val output = ByteArrayOutputStream()
            val writer = ImageIO.getImageWritersByFormatName("jpg").asSequence().firstOrNull()
                ?: throw IllegalStateException("JPEG writer is unavailable")
            try {
                ImageIO.createImageOutputStream(output).use { imageOutput ->
                    writer.output = imageOutput
                    val params = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality
                    }
                    writer.write(null, javax.imageio.IIOImage(image, null, null), params)
                }
            } catch (exception: IOException) {
                throw IllegalArgumentException("Image cannot be encoded", exception)
            } finally {
                writer.dispose()
            }
            val bytes = output.toByteArray()
            if (bytes.size <= MAX_OUTPUT_BYTES) return bytes
            quality -= 0.1f
        }
        throw IllegalArgumentException("Image is too large after normalization")
    }

    fun sha256(source: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source))
}
