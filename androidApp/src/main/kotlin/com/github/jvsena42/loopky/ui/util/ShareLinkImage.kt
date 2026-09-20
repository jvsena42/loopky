package com.github.jvsena42.loopky.ui.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import com.github.jvsena42.loopky.ui.components.qrBitmap
import java.io.File

/**
 * Opens the system share sheet with [text] and, alongside it, the QR code for [link] as a PNG.
 *
 * Falls back to [shareText] if the code cannot be encoded or the file cannot be written — a share
 * without the picture still carries the link, and the in-app sheet has already shown the code.
 *
 * Note that a receiving app decides for itself what to do with both extras: most messengers attach
 * the image and keep the caption, some mail clients put the text in the body, and a few take the
 * image only. The link is inside the code either way, which is why the picture is worth sending.
 */
fun Context.shareLinkWithQr(text: String, link: String, chooserTitle: String) {
    val imageUri = qrShareUri(link)
    if (imageUri == null) {
        shareText(text = text, chooserTitle = chooserTitle)
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, imageUri)
        putExtra(Intent.EXTRA_TEXT, text)
        // The read grant rides on the clip, not on the extra: without this the chooser has no
        // preview to draw and a receiver that resolves the uri itself is refused.
        clipData = ClipData.newUri(contentResolver, SHARE_QR_FILE, imageUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, chooserTitle))
}

/**
 * The QR for [link] written into the cache directory the manifest's `FileProvider` exposes, as a
 * `content://` uri another app may read.
 */
private fun Context.qrShareUri(link: String): Uri? {
    val code = qrBitmap(link, SHARE_QR_PX) ?: return null
    val plated = onWhitePlate(code)
    val dir = File(cacheDir, SHARE_DIR)
    return runCatching {
        dir.mkdirs()
        // One file, overwritten: the previous share's code is of no use to anyone, and a cache
        // directory that only ever grows is a cache directory that eventually gets noticed.
        val file = File(dir, SHARE_QR_FILE)
        file.outputStream().use { plated.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }.getOrNull()
}

/**
 * [code] centred on a white square with a margin.
 *
 * The margin is the quiet zone, which the encoder is asked for only one module of: a code pasted
 * into a chat lands flush against a dark bubble, and a reader that cannot find the border will not
 * lock on to the code inside it.
 */
private fun onWhitePlate(code: Bitmap): Bitmap {
    val side = code.width + SHARE_QR_MARGIN_PX * 2
    val plate = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    Canvas(plate).apply {
        drawColor(Color.WHITE)
        drawBitmap(code, SHARE_QR_MARGIN_PX.toFloat(), SHARE_QR_MARGIN_PX.toFloat(), null)
    }
    return plate
}

/** Big enough to stay sharp when a chat client re-encodes it, small enough to send over mobile data. */
private const val SHARE_QR_PX = 720
private const val SHARE_QR_MARGIN_PX = 48
private const val SHARE_DIR = "share"
private const val SHARE_QR_FILE = "loopky-qr.png"
