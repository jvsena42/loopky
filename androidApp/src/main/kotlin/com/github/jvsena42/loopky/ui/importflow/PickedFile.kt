package com.github.jvsena42.loopky.ui.importflow

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.github.jvsena42.loopky.presentation.importflow.BulkImportError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * What the picker recovered from a `content://` uri: the file spooled to app cache, plus enough of
 * its head to tell what it is.
 *
 * This used to carry the whole file as a `ByteArray`. A `.apkg` is a zip whose collection is 1–5 MB
 * and whose remaining bulk is mp3/jpg blobs, so holding all of it in memory to read a fraction of it
 * put a 32 MB ceiling on the flow and excluded most of the popular AnkiWeb catalogue (#96). Spooled
 * to disk, the reader opens the zip by path and pulls out only the entries it wants.
 */
internal data class PickedFile(val name: String, val path: String, val header: ByteArray) {
    // ByteArray gives identity equality, which is wrong for a data class. Only `name` is ever
    // compared, and equality on the header would say nothing useful anyway.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = name.hashCode()

    /** The spool is ours; nothing else refers to it once the parse has taken what it needs. */
    fun delete() {
        runCatching { File(path).delete() }
    }
}

/** A read that failed in a way the user can act on, rather than a raw exception message. */
internal class FileReadException(val reason: BulkImportError) : Exception(reason.name)

/**
 * Ceiling on a picked file.
 *
 * No longer a memory backstop — the file lands on disk, not in a `ByteArray`, so peak heap tracks
 * the collection inside a `.apkg` rather than the whole archive. What is left is a guard against
 * filling the cache partition, set well above the largest decks anyone actually shares: the biggest
 * in AnkiWeb's popular list is 137 MB.
 */
internal const val MAX_IMPORT_FILE_BYTES = 500L * 1024 * 1024

/**
 * Ceiling on a file that is not a `.apkg`.
 *
 * A `.txt`/`.csv` still becomes one `String` and then one list of rows, so for those the old
 * memory argument holds unchanged. A 20k-card Anki text export is ~2 MB.
 */
internal const val MAX_IMPORT_TEXT_BYTES = 32L * 1024 * 1024

/** Enough to recognise a zip; [ApkgReader.canRead] only looks at the first four bytes. */
private const val HEADER_BYTES = 16

private const val COPY_BUFFER_BYTES = 64 * 1024

/** Shared by [readPickedFile] and [sweepImportSpools], so the sweep cannot miss a name. */
private const val SPOOL_PREFIX = "loopky-import"

private const val SPOOL_SUFFIX = ".bin"

/**
 * Deletes every import spool left behind by a previous process.
 *
 * A spool is deleted by whoever holds it, but nothing gets to run when the process is killed —
 * so a deck opened from *Open with* and then swiped away leaves up to [MAX_IMPORT_FILE_BYTES]
 * of someone's cache to us indefinitely. Call this at process start and only there: every spool
 * is created and deleted within one process, so at that moment none of these can be live.
 */
internal fun File.sweepImportSpools() {
    runCatching {
        listFiles { file ->
            file.name.startsWith(SPOOL_PREFIX) && file.name.endsWith(SPOOL_SUFFIX)
        }?.forEach { it.delete() }
    }
}

/**
 * Copies [uri] into app cache, off the main thread.
 *
 * Streamed rather than read whole: the size check now also happens *as* the copy runs, so a
 * provider that under-reports `SIZE` costs a truncated spool instead of an OOM.
 */
internal suspend fun ContentResolver.readPickedFile(uri: Uri, cacheDir: File): Result<PickedFile> =
    withContext(Dispatchers.IO) {
        runCatching {
            // Checked before reading so an oversized file costs a cursor query, not 500 MB of copy.
            val declared = queryLong(uri, OpenableColumns.SIZE)
            if (declared != null && declared > MAX_IMPORT_FILE_BYTES) {
                throw FileReadException(BulkImportError.TooLarge)
            }

            val spool = File.createTempFile(SPOOL_PREFIX, SPOOL_SUFFIX, cacheDir)
            // A half-written spool is not something the caller can clean up: it never gets a
            // PickedFile to delete. runCatching rather than a catch-and-rethrow so the failure
            // keeps its own type on the way out.
            val header = runCatching { fillFrom(uri, spool) }
                .onFailure { spool.delete() }
                .getOrThrow()

            PickedFile(
                name = displayName(uri) ?: uri.fallbackName(),
                path = spool.absolutePath,
                header = header,
            )
        }
    }

/**
 * The spooled file as text, for the `.txt`/`.csv` path.
 *
 * Two distinct failures, kept apart: past [MAX_IMPORT_TEXT_BYTES] the file is simply too big to
 * become one `String`, while an invalid UTF-8 sequence means the user picked a photo or a PDF.
 * Without the strict decode that used to become U+FFFD soup and "parse" into plausible junk cards.
 */
internal fun PickedFile.readAsText(): Result<String> = runCatching {
    val file = File(path)
    if (file.length() > MAX_IMPORT_TEXT_BYTES) throw FileReadException(BulkImportError.TooLarge)
    file.readBytes().decodeToString(throwOnInvalidSequence = true)
}

/** Streams [uri] into [spool], bounded as it goes, and returns the file's head. */
private fun ContentResolver.fillFrom(uri: Uri, spool: File): ByteArray {
    val input = openInputStream(uri) ?: throw FileReadException(BulkImportError.Unreadable)
    input.use { source -> spool.outputStream().use { source.copyBounded(it) } }
    return spool.readHeader()
}

/**
 * Copies this stream to [output], failing past [MAX_IMPORT_FILE_BYTES].
 *
 * The bound is enforced here rather than only from the provider's declared SIZE, because a provider
 * is not obliged to report one — and the file that under-reports is exactly the one worth stopping.
 */
private fun InputStream.copyBounded(output: OutputStream) {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return
        total += read
        if (total > MAX_IMPORT_FILE_BYTES) throw FileReadException(BulkImportError.TooLarge)
        output.write(buffer, 0, read)
    }
}

private fun File.readHeader(): ByteArray = inputStream().use { input ->
    val buffer = ByteArray(HEADER_BYTES)
    val read = input.read(buffer).coerceAtLeast(0)
    buffer.copyOf(read)
}

/**
 * The real file name.
 *
 * `uri.lastPathSegment` is frequently an opaque document id for a content-provider uri
 * (`msf:1000000123`), which is what the screen used to show and what the deck title fell back to.
 */
private fun ContentResolver.displayName(uri: Uri): String? =
    queryString(uri, OpenableColumns.DISPLAY_NAME)?.takeIf { it.isNotBlank() }

/** Last resort when the provider is not openable and reports no columns at all. */
private fun Uri.fallbackName(): String = lastPathSegment?.substringAfterLast('/').orEmpty()

private fun ContentResolver.queryString(uri: Uri, column: String): String? =
    queryColumn(uri, column) { cursor, index -> cursor.getString(index) }

private fun ContentResolver.queryLong(uri: Uri, column: String): Long? =
    queryColumn(uri, column) { cursor, index -> cursor.getLong(index) }

/**
 * A non-openable provider returns a null cursor or omits the column entirely, so every step here
 * has to tolerate absence rather than assume the OpenableColumns contract holds.
 */
private fun <T> ContentResolver.queryColumn(
    uri: Uri,
    column: String,
    read: (android.database.Cursor, Int) -> T,
): T? = runCatching {
    query(uri, arrayOf(column), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(column)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) read(cursor, index) else null
    }
}.getOrNull()
