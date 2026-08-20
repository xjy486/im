package com.jitong.im.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * Bounds the representation retained in the encrypted offline-upload area.
 * The server still re-decodes and validates the upload authoritatively.
 */
internal object ImageNormalizer {
    const val MAX_LONG_EDGE = 2048
    const val MAX_OUTPUT_BYTES = 5 * 1024 * 1024
    private const val MAX_INPUT_BYTES = 20 * 1024 * 1024
    private const val MAX_DECODE_PIXELS = 20_000_000L

    fun normalize(source: ByteArray): ByteArray {
        require(source.isNotEmpty() && source.size <= MAX_INPUT_BYTES) {
            "Image input is too large"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        require(
            bounds.outWidth > 0
                && bounds.outHeight > 0
                && bounds.outMimeType?.startsWith("image/") == true
                && bounds.outWidth.toLong() * bounds.outHeight <= MAX_DECODE_PIXELS,
        ) { "Image cannot be safely decoded" }

        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size)
            ?: error("Image cannot be safely decoded")
        val scaled = scale(decoded)
        return try {
            encodeJpeg(scaled)
        } finally {
            scaled.recycle()
            decoded.recycle()
        }
    }

    private fun scale(source: Bitmap): Bitmap {
        val scale = minOf(
            1f,
            MAX_LONG_EDGE.toFloat() / maxOf(source.width, source.height).toFloat(),
        )
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        return Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also { target ->
            Canvas(target).apply {
                drawColor(Color.WHITE)
                drawBitmap(
                    source,
                    null,
                    android.graphics.Rect(0, 0, width, height),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
        }
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        var quality = 90
        while (quality >= 40) {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
            val bytes = output.toByteArray()
            if (bytes.size <= MAX_OUTPUT_BYTES) return bytes
            quality -= 10
        }
        error("Image is too large after normalization")
    }
}
