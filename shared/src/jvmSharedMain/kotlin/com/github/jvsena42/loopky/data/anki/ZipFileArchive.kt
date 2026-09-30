package com.github.jvsena42.loopky.data.anki

import java.util.zip.ZipFile

/**
 * [ApkgArchive] over `java.util.zip`, streaming each entry through the bounded read so a crafted
 * archive cannot inflate past [limit] into heap.
 */
internal class ZipFileArchive(private val zip: ZipFile) : ApkgArchive {
    override fun read(name: String, limit: Long): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        return zip.readEntryBounded(entry, limit)
    }
}
