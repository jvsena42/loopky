package com.github.jvsena42.loopky.ui.util

import android.content.Context
import android.content.Intent

/**
 * Opens the system share sheet with [text].
 *
 * The plain-text share, and [shareLinkWithQr]'s fallback when the code cannot be encoded.
 */
fun Context.shareText(text: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, chooserTitle))
}
