package com.github.jvsena42.loopky.data.anki

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Reading one zip entry without letting it decide how much memory or disk it gets.
 *
 * Both helpers enforce the bound twice: once against [ZipEntry.getSize], which is cheap but comes
 * from the archive and so cannot be trusted, and once while the bytes actually flow, which is the
 * enforcement that holds against an archive that lied. See [ApkgLimits].
 *
 * Over-budget is `null`, never a throw. A picture left behind is a reportable outcome the importer
 * already models ([MediaIndex.skipped]); a collection left behind falls through to the next
 * candidate. Neither is worth failing the whole import over.
 */
internal fun ZipFile.readEntryBounded(entry: ZipEntry, limit: Long): ByteArray? {
    if (ApkgLimits.exceedsDeclaredSize(entry.size, limit)) return null
    val sink = ByteArrayOutputStream(entry.size.coerceIn(0L, INITIAL_BUFFER_CEILING).toInt())
    return if (getInputStream(entry).use { it.copyBounded(sink, limit) }) sink.toByteArray() else null
}

/**
 * Streams [entry] into [output]. False when it ran over [limit], in which case [output] holds a
 * partial write the caller must discard.
 */
internal fun ZipFile.copyEntryBounded(entry: ZipEntry, output: OutputStream, limit: Long): Boolean {
    if (ApkgLimits.exceedsDeclaredSize(entry.size, limit)) return false
    return getInputStream(entry).use { it.copyBounded(output, limit) }
}

/** False as soon as more than [limit] bytes have been read, without reading the rest. */
private fun InputStream.copyBounded(output: OutputStream, limit: Long): Boolean {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return true
        total += read
        if (total > limit) return false
        output.write(buffer, 0, read)
    }
}

/**
 * Cap on the pre-sized `ByteArrayOutputStream`. The declared size is a hint from the archive, so
 * sizing the buffer to it verbatim would let a lying header allocate the very heap the limit is
 * there to protect. Growth from a smaller start costs a few copies on the honest case.
 */
private const val INITIAL_BUFFER_CEILING = 1L * 1024 * 1024

private const val COPY_BUFFER_BYTES = 64 * 1024
