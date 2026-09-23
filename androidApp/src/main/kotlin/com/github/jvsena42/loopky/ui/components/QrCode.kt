package com.github.jvsena42.loopky.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.DisplayMetrics
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.github.jvsena42.loopky.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.min

/**
 * A QR code for [content], drawn crisply at [size].
 *
 * Rendered at the pixel size it will occupy rather than at a fixed module count and upscaled: a QR
 * that lands between whole pixels smears its module edges, and a smeared code is a code a phone
 * camera has to be nursed into reading. That is also why nothing here anti-aliases — [ContentScale.FillBounds]
 * over an exactly-sized bitmap is a 1:1 blit.
 *
 * [withMark] punches the Loopky fox into the middle — see [qrBitmap] for what that costs.
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
    withMark: Boolean = false,
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val context = LocalContext.current
    val mark = if (withMark) remember(context) { loopkyQrMark(context) } else null
    val bitmap = remember(content, sizePx, foreground, background, mark) {
        qrBitmap(content, sizePx, foreground.toArgb(), background.toArgb(), mark)?.asImageBitmap()
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

/**
 * The same code as [QrCode], as a plain bitmap — for the callers that hand one to something other
 * than composition, such as the share sheet attaching it to an `ACTION_SEND`.
 *
 * Passing a [mark] raises the correction level from M to H, because the plate it is drawn on
 * destroys the modules underneath it: at H a reader recovers ~30% of the code and the mark covers
 * ~6%, so the margin is wide. Never widen the plate without moving the level with it.
 *
 * `null` when the encoder refuses [content], exactly as [QrCode] draws nothing for it.
 */
fun qrBitmap(
    content: String,
    sizePx: Int,
    fgArgb: Int = Color.Black.toArgb(),
    bgArgb: Int = Color.White.toArgb(),
    mark: Bitmap? = null,
): Bitmap? {
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
                EncodeHintType.ERROR_CORRECTION to
                    if (mark == null) ErrorCorrectionLevel.M else ErrorCorrectionLevel.H,
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
        if (mark != null) drawMark(mark, bgArgb)
    }
}

/**
 * The Loopky fox, cropped to its own artwork, or `null` if the drawable cannot be decoded.
 *
 * Two things this does not leave to the platform. The launcher foreground is drawn inside the
 * adaptive-icon safe zone, so a fifth of every edge is transparent; it is trimmed rather than
 * scaled by a hardcoded factor, so redrawing the icon cannot silently shrink the mark to two
 * thirds of the plate it was sized for. And the **xxxhdpi** art is asked for by name rather than
 * the device's own bucket: the shared PNG's plate is 173px on every phone, so an mdpi device would
 * otherwise upscale 60px of fox into it and send a soft mark that nothing on that device shows.
 */
fun loopkyQrMark(context: Context): Bitmap? {
    val drawable = ResourcesCompat.getDrawableForDensity(
        context.resources,
        R.drawable.ic_launcher_foreground,
        DisplayMetrics.DENSITY_XXXHIGH,
        null,
    )
    return (drawable as? BitmapDrawable)?.bitmap?.trimTransparent()
}

private fun Bitmap.trimTransparent(): Bitmap {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            // Above the fringe an anti-aliased edge leaves behind, not above zero: the alpha ramp
            // runs several pixels out and trimming to it gives the padding back.
            if (pixels[row + x] ushr ALPHA_SHIFT < ALPHA_FLOOR) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    if (right < left || bottom < top) return this
    return Bitmap.createBitmap(this, left, top, right - left + 1, bottom - top + 1)
}

/**
 * **Destroys the modules under the centre.** Only ever called on a matrix encoded at
 * [ErrorCorrectionLevel.H] — see [qrBitmap].
 */
private fun Bitmap.drawMark(mark: Bitmap, plateArgb: Int) {
    val canvas = Canvas(this)
    val plate = width * MARK_PLATE_FRACTION
    val origin = (width - plate) / 2f
    val corner = plate * MARK_CORNER_FRACTION
    canvas.drawRoundRect(
        RectF(origin, origin, origin + plate, origin + plate),
        corner,
        corner,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = plateArgb },
    )
    val inset = plate * MARK_INSET_FRACTION
    val box = plate - inset * 2
    val scale = min(box / mark.width, box / mark.height)
    val markWidth = mark.width * scale
    val markHeight = mark.height * scale
    val markLeft = origin + (plate - markWidth) / 2f
    val markTop = origin + (plate - markHeight) / 2f
    canvas.drawBitmap(
        mark,
        null,
        RectF(markLeft, markTop, markLeft + markWidth, markTop + markHeight),
        Paint(Paint.FILTER_BITMAP_FLAG),
    )
}

private val DEFAULT_SIZE = 220.dp
private const val QUIET_ZONE_MODULES = 1
private const val MARK_PLATE_FRACTION = 0.24f
private const val MARK_CORNER_FRACTION = 0.28f
private const val MARK_INSET_FRACTION = 0.1f
private const val ALPHA_SHIFT = 24
private const val ALPHA_FLOOR = 8
