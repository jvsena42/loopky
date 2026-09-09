package com.github.jvsena42.loopky.ui.util

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * The file this intent is handing us, if any.
 *
 * Two ways in, mirroring [pubkyLink]: a file manager or a browser download sends `ACTION_VIEW`
 * with the uri as data, while a chat client sends `ACTION_SEND` with it in `EXTRA_STREAM`.
 *
 * **`content:` only.** That is what keeps this from swallowing the `pubky://` and `loopky://` links
 * the same two actions also deliver — a `VIEW` on one of those has a scheme this rejects, and
 * shared text has no stream at all.
 *
 * `file:` used to be accepted too, and that was an arbitrary-read primitive. `MainActivity` is
 * exported, so any installed app can launch it with an *explicit* intent, which bypasses every
 * `<intent-filter>` and with it the `.apkg` path patterns — leaving this function as the only gate.
 * A `file:///data/data/com.github.jvsena42.loopky/...` uri is then opened with Loopky's own uid,
 * spooled, and rendered in the import preview, from where the user can publish it. Nobody loses
 * anything by the removal: an app targeting Android 7+ cannot send a `file:` uri at all without
 * `FileUriExposedException`, so no current sender uses one.
 *
 * Nothing here looks at the MIME type or the extension to decide *what* the file is. The manifest
 * filters use those to decide whether Loopky is worth offering; `ApkgReader.canRead` sniffs the
 * bytes once the file is in hand, and that is the actual type check.
 */
fun Intent.importFileUri(): Uri? {
    val uri = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    } ?: return null
    return uri.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
}
