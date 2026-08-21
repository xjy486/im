package com.jitong.im.desktop.media

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImageNormalizerTest {
    @Test
    fun normalizes_images_to_a_jpeg_with_a_bounded_long_edge() {
        val source = BufferedImage(4096, 1024, BufferedImage.TYPE_INT_ARGB).also {
            val graphics = it.createGraphics()
            graphics.color = Color.MAGENTA
            graphics.fillRect(0, 0, it.width, it.height)
            graphics.dispose()
        }
        val input = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()

        val normalized = ImageNormalizer.normalize(input)
        val decoded = ImageIO.read(normalized.inputStream())

        assertEquals(2048, decoded.width)
        assertEquals(512, decoded.height)
        assertEquals("JPEG", ImageIO.getImageReadersByFormatName("jpg").next().formatName)
    }

    @Test
    fun rejects_non_image_bytes() {
        assertFailsWith<IllegalArgumentException> {
            ImageNormalizer.normalize("not an image".toByteArray())
        }
    }
}
