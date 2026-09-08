package com.github.jvsena42.loopky.ui.util

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the clipboard held when the user tapped Paste in the image sheet. */
sealed interface ClipboardContent {

    /** Whatever text was on the clipboard — an image address, or just words to search for. */
    data class Text(val text: String) : ClipboardContent

    /**
     * An image copied as *bytes* (Chrome's "Copy image"), which Loopky does not take yet — see
     * [readClipboard] for why, and the follow-up issue for what taking it would need.
     */
    data object ImageClip : ClipboardContent

    /** Nothing usable. */
    data object Empty : ClipboardContent
}

/**
 * Reads the clipboard for the image sheet's Paste button.
 *
 * **An image clip is identified and refused without the provider being touched at all**, and that
 * restraint is the whole design of this function. A clip written by another app is a handle to
 * *its* content provider, so `getType`, `openInputStream` and `coerceToText` are each IPC into a
 * process that may do arbitrary work to answer — Chrome materializes the copied image on demand.
 * `coerceToText` is the one that surprises: on a `content:` uri it does not format the uri, it
 * *opens and reads the stream*. Calling it on a Chrome image clip froze the app outright. Only
 * [ClipDescription] and the item's uri are read here, both of which are local metadata that
 * arrived with the clip.
 *
 * The rest still runs off the main thread and under [READ_TIMEOUT_MS]: `primaryClip` is itself a
 * binder call, and a clipboard service under no obligation to answer must not leave the button
 * pressed forever.
 *
 * Reading a clipboard from Android 12 onward shows the system's "pasted from clipboard" toast.
 * That is the correct disclosure for a button the user just pressed, and the reason this is not
 * read speculatively anywhere else.
 */
suspend fun Context.readClipboard(): ClipboardContent = withContext(Dispatchers.IO) {
    withTimeoutOrNull(READ_TIMEOUT_MS) {
        runCatching { readClip() }
            .onFailure { Log.w(TAG, "Clipboard could not be read", it) }
            .getOrNull()
    } ?: ClipboardContent.Empty
}

private fun Context.readClip(): ClipboardContent {
    val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ClipboardContent.Empty
    if (clip.itemCount == 0) return ClipboardContent.Empty
    val item = clip.getItemAt(0)
    val isImageClip = clip.description?.hasMimeType(IMAGE_MIME_PATTERN) == true ||
        item.uri?.scheme == ContentResolver.SCHEME_CONTENT
    if (isImageClip) return ClipboardContent.ImageClip
    val text = item.text?.toString()?.trim().orEmpty()
    return if (text.isEmpty()) ClipboardContent.Empty else ClipboardContent.Text(text)
}

private const val IMAGE_MIME_PATTERN = "image/*"
private const val READ_TIMEOUT_MS = 5_000L
private const val TAG = "Loopky/Clipboard"
