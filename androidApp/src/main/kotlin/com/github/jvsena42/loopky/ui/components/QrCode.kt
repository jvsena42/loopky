package com.github.jvsena42.loopky.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.jvsena42.loopky.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code for [content], drawn crisply at [size].
 *
 * Rendered at the pixel size it will occupy rather than at a fixed module count and upscaled: a QR
 * that lands between whole pixels smears its module edges, and a smeared code is a code a phone
 * camera has to be nursed into reading. That is also why nothing here anti-aliases — [ContentScale.FillBounds]
 * over an exactly-sized bitmap is a 1:1 blit.
 *
 * Returns nothing to draw if the encoder refuses [content] (too long for any version at this
 * correction level). Callers show the URL as text alongside, so a missing code degrades to a
 * copyable link rather than a dead screen.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.onboarding_qr_content_description),
    size: Dp = DEFAULT_SIZE,
    foreground: Color = Color.Black,
    background: Color = Color.White,
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(content, sizePx, foreground, background) {
        encodeQr(content, sizePx, foreground.toArgb(), background.toArgb())
    } ?: return

    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        // The bitmap is already exactly `sizePx` square, so this scales by 1 and keeps the
        // modules on pixel boundaries.
        contentScale = ContentScale.FillBounds,
    )
}

private fun encodeQr(content: String, sizePx: Int, fgArgb: Int, bgArgb: Int): ImageBitmap? {
    if (content.isEmpty() || sizePx <= 0) return null
    val matrix = runCatching {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                // Medium recovers ~15% of the code. Enough for a screen — which has no fingerprints,
                // no crease and no coffee ring — while keeping the modules large, and large modules
                // are what a phone held at arm's length in front of a tablet actually needs.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                // ZXing's default quiet zone is 4 modules, which at this size eats most of the
                // panel. The composable draws on its own light plate, which serves the same purpose.
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
            ),
        )
    }.getOrNull() ?: return null

    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val row = y * sizePx
        for (x in 0 until sizePx) {
            pixels[row + x] = if (matrix[x, y]) fgArgb else bgArgb
        }
    }
    return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    }.asImageBitmap()
}

private val DEFAULT_SIZE = 220.dp
private const val QUIET_ZONE_MODULES = 1
