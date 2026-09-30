package com.github.jvsena42.loopky.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

/**
 * [MediaProcessor] backed by `javax.imageio`, running headless.
 *
 * `java.awt.headless` is forced to true from the CLI entry point rather than assumed: ImageIO's
 * raster and JPEG paths do not need a display, but AWT will try to open one on first use if
 * nobody has said otherwise, and on a box with no X server that is an exception rather than a
 * fallback. A headless JRE with no AWT at all is also a supported deployment, so a decode failure
 * here has to **degrade**: [compressImage] hands the original bytes back unchanged rather than
 * throwing, and the caller uploads a picture that is merely larger than intended.
 *
 * That is the right trade for the CLI's actual workload, where card images are overwhelmingly
 * remote refs (a URL, #167) and nothing crosses the wire at all.
 */
class JvmMediaProcessor : MediaProcessor {

    override suspend fun compressImage(
        bytes: ByteArray,
        maxDimension: Int,
        quality: Int,
    ): ProcessedImage = withContext(Dispatchers.Default) {
        val decoded = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return@withContext passThrough(bytes)

        val scaled = decoded.downscaledTo(maxDimension)
        runCatching { ProcessedImage(scaled.toJpeg(quality), "image/jpeg", scaled.width, scaled.height) }
            .getOrElse { passThrough(bytes) }
    }

    /**
     * The image as it arrived, with the dimensions we know and nothing invented.
     *
     * Zero width and height rather than a guess: a caller that needs them can decode for itself,
     * and a fabricated aspect ratio would be stored on the card and shown by both apps.
     */
    private fun passThrough(bytes: ByteArray) = ProcessedImage(bytes, "image/jpeg", 0, 0)

    private fun BufferedImage.downscaledTo(maxDimension: Int): BufferedImage {
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return this
        val ratio = maxDimension.toDouble() / longest
        val target = BufferedImage(
            (width * ratio).roundToInt().coerceAtLeast(1),
            (height * ratio).roundToInt().coerceAtLeast(1),
            // TYPE_INT_RGB, not the source type: a PNG with alpha drawn straight into a JPEG
            // writer comes out with the channels reordered, which looks like a colour-space bug
            // rather than a missing background.
            BufferedImage.TYPE_INT_RGB,
        )
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            graphics.drawImage(this, 0, 0, target.width, target.height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun BufferedImage.toJpeg(quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val output = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality.coerceIn(1, 100) / 100f
            }
            // Flattened first: a source with an alpha channel makes the JPEG writer throw
            // "Bogus input colorspace", which is the one failure that would otherwise reach the
            // caller as a hard error instead of a larger picture.
            writer.write(null, IIOImage(withoutAlpha(), null, null), params)
            writer.dispose()
        }
        return output.toByteArray()
    }

    private fun BufferedImage.withoutAlpha(): BufferedImage {
        if (!colorModel.hasAlpha() && type == BufferedImage.TYPE_INT_RGB) return this
        val flattened = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = flattened.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.drawImage(this, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return flattened
    }
}
