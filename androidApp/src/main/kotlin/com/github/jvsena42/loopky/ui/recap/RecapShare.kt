package com.github.jvsena42.loopky.ui.recap

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.github.jvsena42.loopky.domain.model.RecapShareTarget

/**
 * Hands the recap to [target]'s own app, with what that app can actually take.
 *
 * Three steps down, each tried only when the one above cannot start: the app itself with the
 * image and caption, that network's web composer with the caption alone (a URL cannot carry a
 * picture), and the system chooser. Instagram has no web composer and discards any caption, so it
 * goes straight from its app to the chooser.
 *
 * No `<queries>` entry is needed for this: package visibility limits what an app can *ask* about,
 * not what it can start, and a missing app surfaces as [ActivityNotFoundException] either way.
 */
fun Context.shareRecap(target: RecapShareTarget, text: String, image: Uri?, chooserTitle: String) {
    val send = sendIntent(text.takeIf { target.takesText }, image)
    val attempts = listOfNotNull(
        target.packageName()?.let { Intent(send).setPackage(it) },
        target.webComposer(text)?.let { Intent(Intent.ACTION_VIEW, it) },
        Intent.createChooser(send, chooserTitle),
    )
    for (intent in attempts) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            continue
        }
    }
}

private fun Context.sendIntent(text: String?, image: Uri?): Intent = Intent(Intent.ACTION_SEND).apply {
    type = if (image != null) "image/png" else "text/plain"
    text?.let { putExtra(Intent.EXTRA_TEXT, it) }
    if (image != null) {
        putExtra(Intent.EXTRA_STREAM, image)
        // The read grant rides on the clip, not on the extra — see shareLinkWithQr.
        clipData = ClipData.newUri(contentResolver, "recap", image)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun RecapShareTarget.packageName(): String? = when (this) {
    RecapShareTarget.X -> "com.twitter.android"
    RecapShareTarget.Threads -> "com.instagram.barcelona"
    RecapShareTarget.Instagram -> "com.instagram.android"
    RecapShareTarget.WhatsApp -> "com.whatsapp"
    RecapShareTarget.Pubky, RecapShareTarget.More -> null
}

private fun RecapShareTarget.webComposer(text: String): Uri? = when (this) {
    RecapShareTarget.X -> "https://twitter.com/intent/tweet"
    RecapShareTarget.Threads -> "https://www.threads.net/intent/post"
    RecapShareTarget.WhatsApp -> "https://wa.me/"
    RecapShareTarget.Pubky, RecapShareTarget.Instagram, RecapShareTarget.More -> null
}?.let { Uri.parse(it).buildUpon().appendQueryParameter("text", text).build() }
