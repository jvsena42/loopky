package com.github.jvsena42.loopky.data.anki

import android.database.sqlite.SQLiteDatabase
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import java.io.File

/**
 * Android `.apkg` reader.
 *
 * Everything but opening the collection lives in [JvmApkgReader], shared with the desktop JVM
 * target. What is Android's alone is that SQLite is in the platform, so this adds no
 * dependencies — see [AnkiDbOpener].
 */
actual object ApkgReader {

    private val reader = JvmApkgReader(
        object : AnkiDbOpener {
            override fun <T> use(file: File, read: (AnkiDb) -> T): T =
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    .use { read(AndroidAnkiDb(it)) }
        },
    )

    actual fun canRead(header: ByteArray): Boolean = reader.canRead(header)

    actual suspend fun readNotes(
        path: String,
        mapping: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): Result<ApkgImport> = reader.readNotes(path, mapping, compressImage)
}
