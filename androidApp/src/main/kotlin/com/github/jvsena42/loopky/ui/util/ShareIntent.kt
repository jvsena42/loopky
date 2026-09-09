package com.github.jvsena42.loopky.ui.util

import android.content.Context
import android.content.Intent

/**
 * Opens the system share sheet with [text].
 *
 * Both share buttons in the app previously did nothing: `DeckDetailEffect.Share` was consumed
 * by an empty lambda and `ProfileEffect.ShareProfile` by a TODO, and no `ACTION_SEND` existed
 * anywhere in the codebase.
 */
fun Context.shareText(text: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, chooserTitle))
}
