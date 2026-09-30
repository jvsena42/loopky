package com.github.jvsena42.loopky.data.anki

import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Opens an Anki collection that has been spooled to [File] and hands its rows to [read].
 *
 * The one part of the `.apkg` read that differs between the JVMs Loopky runs on: Android has
 * SQLite in the platform (`android.database.sqlite`), a desktop JVM does not and reaches for a
 * JDBC driver. Everything either side of it — the zip, the field parsing, the media, the counts —
 * is [JvmApkgReader], so the two cannot drift into reading the same collection differently. That
 * is the same reason `ApkgCollection.kt` holds every query in `commonMain`.
 *
 * Read-only, and closed before [use] returns.
 *
 * A plain interface rather than a `fun interface`: SAM conversion cannot express a method with
 * its own type parameter, and [use] needs one so the caller's result type survives the call.
 */
internal interface AnkiDbOpener {
    fun <T> use(file: File, read: (AnkiDb) -> T): T
}

/**
 * `.apkg` reader for every target that runs on a JVM, built on `java.util.zip` plus whatever
 * SQLite the platform can offer through [AnkiDbOpener].
 *
 * An `.apkg` is a zip holding a SQLite collection, a media manifest and numbered blobs; see
 * [ApkgReader] for what the shape of the result means.
 */
internal class JvmApkgReader(private val openDb: AnkiDbOpener) {

    fun canRead(header: ByteArray): Boolean =
        header.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { header[it] == ZIP_MAGIC[it] }

    suspend fun readNotes(
        path: String,
        mapping: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): Result<ApkgImport> = withContext(Dispatchers.IO) {
        runCatching {
            ZipFile(path).use { zip -> zip.readArchive(mapping, compressImage) }
        }
    }

    private suspend fun ZipFile.readArchive(
        mapping: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): ApkgImport {
        val candidates = extractCollections()
        if (candidates.isEmpty()) {
            throw ApkgException(
                ApkgFailure.UnsupportedFormat,
                "That .apkg has no readable collection.",
            )
        }
        return try {
            readFirstUsable(candidates, mapping, compressImage)
        } finally {
            candidates.forEach { it.file.deleteWithSideFiles() }
        }
    }

    /**
     * The spool file **and its `-wal`/`-shm` siblings**.
     *
     * A collection whose header bytes say WAL makes SQLite create both beside the file it opens,
     * and a read-only connection cannot take the exclusive lock needed to unlink them at `close()`
     * — so deleting only the main file left a 32 KB `-shm` and an empty `-wal` in `java.io.tmpdir`
     * on every such import, accumulating forever. Genuine Anki exports switch to
     * `journal_mode=delete` before packing, so this needs a hand-edited or unusually-produced file.
     *
     * **Not fixed with `immutable=1`.** That suppresses the side files by promising the database
     * cannot change, and SQLite then refuses a WAL-header collection outright with
     * `SQLITE_CANTOPEN` — trading a tidiness problem for an import that fails.
     */
    private fun File.deleteWithSideFiles() {
        delete()
        listOf("-wal", "-shm").forEach { suffix -> File("$path$suffix").delete() }
    }

    /**
     * The first candidate that actually holds a deck.
     *
     * Since Anki 2.1.50 an export ships a **legacy stub** `collection.anki2` beside the real
     * `collection.anki21`, holding one note that reads "Please update to the latest Anki version".
     * [COLLECTION_NAMES] prefers the newer file, but a stub can still be all there is — either
     * because the export is odd, or because the real collection is the zstd variant this build
     * skips. Falling through the candidates rather than committing to the first is what keeps a
     * strange export from being reported as an empty one.
     */
    private suspend fun ZipFile.readFirstUsable(
        candidates: List<Candidate>,
        mapping: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): ApkgImport {
        var sawStub = false
        candidates.forEach { candidate ->
            val import = readCollection(candidate.file, mapping, compressImage)
            if (import.noteCount > 0) {
                Log.d(
                    TAG,
                    "apkg: using ${candidate.name} — ${import.noteCount} notes, " +
                        "${import.notes.size} cards, ${import.imagesImported} images, " +
                        "${import.dropped.total} dropped",
                )
                return import
            }
            if (import.isLegacyStub) sawStub = true
            Log.d(TAG, "apkg: ${candidate.name} held nothing usable (stub=${import.isLegacyStub})")
        }
        if (sawStub) {
            throw ApkgException(
                ApkgFailure.LegacyStubOnly,
                "That .apkg holds only Anki's legacy compatibility stub.",
            )
        }
        return ApkgImport(deckName = null)
    }

    /**
     * Pull every readable SQLite collection out of the zip onto disk, in preference order.
     *
     * A SQLite driver opens a path, not a stream, so the collection has to land in a file — but
     * only the collection: the media blobs stay in the archive until a card turns out to need one.
     * `collection.anki21b` is zstd-compressed, which is the one thing here that would need a real
     * dependency, so it is skipped and reported rather than half-handled.
     */
    private fun ZipFile.extractCollections(): List<Candidate> =
        COLLECTION_NAMES.mapNotNull { name ->
            val entry = getEntry(name) ?: return@mapNotNull null
            val file = File.createTempFile("loopky-apkg", ".sqlite")
            // Bounded: the 500 MB picked-file limit bounds the archive, not what an entry inflates
            // to, and a collection is the one entry we copy to disk whole. Over budget the partial
            // file goes and the candidate is skipped, which falls through to the next name and
            // ends as "no readable collection" rather than a filled cache partition.
            val copied = file.outputStream().use { output ->
                copyEntryBounded(entry, output, ApkgLimits.MAX_COLLECTION_BYTES)
            }
            if (!copied) {
                Log.d(TAG, "apkg: $name is over ${ApkgLimits.MAX_COLLECTION_BYTES} bytes inflated — skipped")
                file.delete()
                return@mapNotNull null
            }
            Candidate(name = name, file = file)
        }

    private data class Candidate(val name: String, val file: File)

    private suspend fun ZipFile.readCollection(
        file: File,
        requested: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): ApkgImport {
        val raw = openDb.use(file) { db -> db.readRawNotes() }
        if (raw.rows.isEmpty()) {
            return ApkgImport(deckName = raw.deckName, isLegacyStub = raw.stubOnly)
        }

        val parsed = raw.rows.map { row -> row.fields.map(::parseAnkiField) }
        val fieldCount = parsed.maxOf { it.size }
        val mapping = requested ?: chooseDefaultFields(parsed, fieldCount)

        val media = MediaIndex(ZipFileArchive(this))
        var dropped = ApkgDropped()
        val notes = mutableListOf<BulkNote>()
        // Only the notes that became cards suggest tags: a tag on a note this deck dropped
        // describes nothing the user is about to publish.
        val noteTags = mutableListOf<String>()
        parsed.forEachIndexed { index, fields ->
            val images = media.resolve(fields, mapping, compressImage)
            if (images == null) {
                dropped = dropped.copy(missingMedia = dropped.missingMedia + 1)
                return@forEachIndexed
            }
            val cards = ankiNoteToCards(fields, mapping, images)
            if (cards.isEmpty()) {
                dropped = dropped.tally(fields, mapping)
                return@forEachIndexed
            }
            notes += cards
            raw.rows[index].tags.takeIf { it.isNotBlank() }?.let(noteTags::add)
        }

        return ApkgImport(
            deckName = raw.deckName,
            deckDescription = raw.deckDescription,
            suggestedTags = suggestDeckTags(noteTags),
            fieldNames = raw.fieldNames(fieldCount),
            fieldSamples = raw.fieldSamples(fieldCount),
            mapping = mapping,
            notes = notes,
            noteCount = raw.rows.size,
            reversible = raw.reversible,
            dropped = dropped,
            imagesImported = media.imported,
            imagesSkipped = media.skipped,
            isLegacyStub = raw.stubOnly,
        )
    }

    /** Which of the two "this note had nothing to show" cases this was, so the summary can say. */
    private fun ApkgDropped.tally(fields: List<AnkiField>, mapping: ApkgFieldMapping): ApkgDropped {
        val front = fields.getOrNull(mapping.frontOrd)?.isEmpty != false
        val back = fields.getOrNull(mapping.backOrd)?.isEmpty != false
        return if (front && back) copy(empty = empty + 1) else copy(halfEmpty = halfEmpty + 1)
    }

    private companion object {
        const val TAG = "Loopky/ApkgReader"

        /**
         * Newest-first, because a modern export's `collection.anki2` is a stub (see
         * `readFirstUsable`) and the real deck lives in `collection.anki21`. Oldest-first is how
         * every current AnkiWeb deck came to import as "no cards". `collection.anki21b` is absent
         * on purpose: it is zstd, the one variant that would need a new dependency.
         */
        val COLLECTION_NAMES = listOf("collection.anki21", "collection.anki2")

        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
