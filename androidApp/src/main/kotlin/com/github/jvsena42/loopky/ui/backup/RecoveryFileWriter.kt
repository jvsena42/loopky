package com.github.jvsena42.loopky.ui.backup

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Write a recovery file the user chose a location for.
 *
 * **Decodes the Base64 first, on purpose.** The FFI hands back the encrypted file Base64-encoded,
 * but a recovery file on disk is *raw bytes* — that is what pubky-app writes as `recovery.pkarr`
 * and what Pubky Ring and pubky.app read back. Writing the Base64 text verbatim would produce a
 * file only Loopky could open, and the failure would surface much later, on another device, as
 * "this recovery file is corrupt".
 */
@OptIn(ExperimentalEncodingApi::class)
internal suspend fun ContentResolver.writeRecoveryFile(uri: Uri, base64: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = Base64.decode(base64)
            openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open the chosen location")
        }
    }
