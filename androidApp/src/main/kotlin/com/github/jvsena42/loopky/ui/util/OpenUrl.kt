package com.github.jvsena42.loopky.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Opens [url] in the user's browser.
 *
 * Prefer this over `LocalUriHandler`, which throws when no activity can handle the intent — an
 * attribution link is not worth crashing the image sheet over.
 */
fun Context.openUrl(url: String) {
    if (url.isBlank()) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }.onFailure { Log.w("Loopky/OpenUrl", "No handler for $url", it) }
}
