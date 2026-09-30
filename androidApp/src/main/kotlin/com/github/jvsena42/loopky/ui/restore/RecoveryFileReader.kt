package com.github.jvsena42.loopky.ui.restore

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Read a recovery file the user picked, as Base64.
 *
 * **The encoding is the whole point of this function.** A recovery file on disk is *raw bytes* —
 * `"pubky.org/recovery\n"` followed by the Argon2id-encrypted key, which is how pubky-app writes
 * `recovery.pkarr` and how Pubky Ring reads one. The FFI's `decrypt_recovery_file`, by contrast,
 * takes Base64. Getting this backwards produces a file no other Pubky app can open, or a decrypt
 * that fails on a perfectly good file, and neither failure points at the encoding.
 *
 * No spooling, unlike the deck import path: a recovery file is a couple of hundred bytes, and
 * [MAX_BYTES] rejects anything that plainly is not one before it is read into memory.
 */
@OptIn(ExperimentalEncodingApi::class)
internal suspend fun ContentResolver.readRecoveryFile(uri: Uri): Result<PickedRecoveryFile> =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = openInputStream(uri)?.use { input ->
                // +1 so a file at exactly the cap is still detected as over it.
                input.readNBytes(MAX_BYTES + 1)
            } ?: error("Could not open the chosen file")

            require(bytes.isNotEmpty()) { "The chosen file is empty" }
            require(bytes.size <= MAX_BYTES) { "That file is too large to be a recovery file" }

            PickedRecoveryFile(name = displayName(uri), base64 = Base64.encode(bytes))
        }
    }

private fun ContentResolver.displayName(uri: Uri): String {
    val fromProvider = runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment ?: DEFAULT_NAME
}

internal data class PickedRecoveryFile(val name: String, val base64: String)

/** A real recovery file is ~100 bytes; this is generous and still rejects a picked photo. */
private const val MAX_BYTES = 64 * 1024
private const val DEFAULT_NAME = "recovery.pkarr"
