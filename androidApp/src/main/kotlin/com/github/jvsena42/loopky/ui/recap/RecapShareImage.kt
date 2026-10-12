@file:Suppress("MagicNumber")

package com.github.jvsena42.loopky.ui.recap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.github.jvsena42.loopky.domain.model.RecapImageFormat
import com.github.jvsena42.loopky.ui.components.loopkyQrMark
import java.io.File

/**
 * The recap as a picture, in the proportions [format] asks for.
 *
 * Drawn with `android.graphics` rather than captured from the composable: a capture is the size
 * of the device it ran on, and each network wants its own fixed pixel size. Every measure is in
 * units of the short side, so the three formats are one layout rather than three — which is also
 * why this file is a page of bare numbers: they are the design, in 1080ths.
 */
fun renderRecapImage(context: Context, text: RecapCardText, format: RecapImageFormat): Bitmap {
    val width = format.width
    val height = format.height
    // A story is read at arm's length on a phone held upright, with twice the height to fill.
    val scale = if (format == RecapImageFormat.Story) STORY_SCALE else 1f
    val unit = minOf(width, height) / DESIGN_SIDE * scale
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    canvas.drawRect(
        0f, 0f, width.toFloat(), height.toFloat(),
        Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                ACCENT_ORANGE, ACCENT_PURPLE, Shader.TileMode.CLAMP,
            )
        },
    )
    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(GLOW_ALPHA, 255, 255, 255) }
    canvas.drawCircle(width * 0.92f, height * 0.08f, 300 * unit, glow)
    canvas.drawCircle(width * 0.05f, height * 0.95f, 220 * unit, glow)

    val margin = 84 * unit
    val textWidth = (width - margin * 2).toInt()
    val blocks = buildList {
        add(block(text.title, 46 * unit, Color.argb(MUTED_ALPHA, 255, 255, 255), textWidth, bold = false, gapAfter = 18 * unit))
        add(block(text.headline, 92 * unit, Color.WHITE, textWidth, gapAfter = 40 * unit))
        text.stats.forEach { add(block(it, 46 * unit, Color.WHITE, textWidth, gapAfter = 14 * unit)) }
    }
    val pillHeight = 84 * unit
    val brandHeight = 96 * unit
    val contentHeight = brandHeight + 56 * unit + blocks.sumOf { (it.layout.height + it.gapAfter).toDouble() }.toFloat() +
        26 * unit + pillHeight
    // Centred in whatever room the format has: a story is nearly twice as tall as the content.
    var y = ((height - contentHeight - FOOTER_BAND * unit) / 2f).coerceAtLeast(margin)

    drawBrand(canvas, context, margin, y, brandHeight, unit)
    y += brandHeight + 56 * unit
    blocks.forEach { block ->
        canvas.save()
        canvas.translate(margin, y)
        block.layout.draw(canvas)
        canvas.restore()
        y += block.layout.height + block.gapAfter
    }
    y += 26 * unit
    drawPill(canvas, text.persona, margin, y, pillHeight, unit)

    val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(MUTED_ALPHA, 255, 255, 255)
        textSize = 34 * unit
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawText(text.tagline, margin, height - margin, footer)
    canvas.drawText(SITE, width - margin - footer.measureText(SITE), height - margin, footer)
    return bitmap
}

private class TextBlock(val layout: StaticLayout, val gapAfter: Float)

@Suppress("LongParameterList")
private fun block(
    text: String,
    size: Float,
    color: Int,
    width: Int,
    bold: Boolean = true,
    gapAfter: Float,
): TextBlock {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, LINE_SPACING)
        .setMaxLines(MAX_LINES)
        .build()
    return TextBlock(layout, gapAfter)
}

@Suppress("LongParameterList")
private fun drawBrand(canvas: Canvas, context: Context, x: Float, y: Float, size: Float, unit: Float) {
    val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    canvas.drawRoundRect(RectF(x, y, x + size, y + size), 26 * unit, 26 * unit, plate)
    loopkyQrMark(context)?.let { fox ->
        val inset = size * 0.14f
        canvas.drawBitmap(fox, null, RectF(x + inset, y + inset, x + size - inset, y + size - inset), Paint(Paint.FILTER_BITMAP_FLAG))
    }
    val word = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 52 * unit
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawText(WORDMARK, x + size + 24 * unit, y + size / 2f - (word.descent() + word.ascent()) / 2f, word)
}

@Suppress("LongParameterList")
private fun drawPill(canvas: Canvas, label: String, x: Float, y: Float, height: Float, unit: Float) {
    val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_ORANGE
        textSize = 40 * unit
        typeface = Typeface.DEFAULT_BOLD
    }
    val width = text.measureText(label) + 72 * unit
    val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    canvas.drawRoundRect(RectF(x, y, x + width, y + height), height / 2f, height / 2f, plate)
    canvas.drawText(label, x + 36 * unit, y + height / 2f - (text.descent() + text.ascent()) / 2f, text)
}

/**
 * [bitmap] written where the manifest's `FileProvider` can serve it. One file per format,
 * overwritten: last year's share is of no use to anyone.
 */
fun Context.recapShareUri(bitmap: Bitmap, format: RecapImageFormat): Uri? = runCatching {
    val dir = File(cacheDir, SHARE_DIR)
    dir.mkdirs()
    val file = File(dir, "loopky-recap-${format.name.lowercase()}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
}.getOrNull()

// The share image is one fixed picture in both themes, so it takes the light palette's accents
// directly: LoopkyTheme is a composition local and this is not composition.
private val ACCENT_ORANGE = Color.rgb(0xFF, 0x5C, 0x00)
private val ACCENT_PURPLE = Color.rgb(0x7A, 0x4C, 0xFF)
private const val DESIGN_SIDE = 1080f
private const val STORY_SCALE = 1.3f
private const val FOOTER_BAND = 120
private const val GLOW_ALPHA = 28
private const val MUTED_ALPHA = 220
private const val LINE_SPACING = 1.08f
private const val MAX_LINES = 3
private const val PNG_QUALITY = 100
private const val SHARE_DIR = "share"
private const val WORDMARK = "Loopky"
private const val SITE = "loopky.app"
