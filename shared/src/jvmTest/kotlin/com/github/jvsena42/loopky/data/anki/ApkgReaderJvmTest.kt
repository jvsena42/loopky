package com.github.jvsena42.loopky.data.anki

import com.github.jvsena42.loopky.domain.model.DraftCardImage
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the desktop `.apkg` reader can actually open a collection.
 *
 * Written after shipping a JDBC URL that was never interpolated — `"jdbc:sqlite:file:${'$'}{…}"`,
 * where `${'$'}` is a Kotlin template producing a literal `$`, so the constant reached the driver
 * with `${file.absolutePath}` in it verbatim. Every desktop import failed at
 * "unable to open database file", which the reader then reported as *"That .apkg has no readable
 * collection"* — a wrong diagnosis pointing at the user's file.
 *
 * Nothing caught it because the shared suite drives `JvmApkgReader`'s callers, never the opener,
 * and the CLI has no `.apkg` entry point yet. The only test with teeth is one that builds a real
 * zip around a real SQLite file and reads it back, so this does.
 */
class ApkgReaderJvmTest {

    @Test
    fun `reads notes out of a real apkg`() = runTest {
        val apkg = writeApkg(
            "hola" to "hello",
            "adiós" to "goodbye",
        )

        val result = ApkgReader.readNotes(apkg.absolutePath, mapping = null, compressImage = ::unusedImage)

        val import = result.getOrThrow()
        assertEquals(2, import.noteCount)
        assertEquals(listOf("hola", "adiós"), import.notes.map { it.front })
        assertEquals(listOf("hello", "goodbye"), import.notes.map { it.back })
    }

    /** Non-ASCII survives the zip, the collection and the field split unchanged. */
    @Test
    fun `round-trips accented text`() = runTest {
        val apkg = writeApkg("Açaí" to "Açaí palm")

        val import = ApkgReader.readNotes(apkg.absolutePath, null, ::unusedImage).getOrThrow()

        assertEquals("Açaí", import.notes.single().front)
    }

    /**
     * The collection is opened by a SQLite **URI**, and a path is not one (#301).
     *
     * Driven through [sqliteReadOnlyUrl] rather than through `readNotes`, because the file the
     * driver actually opens is a `createTempFile` spool — so an import from a path with a space in
     * it exercises the *zip* open and passes whether the URL is right or wrong. That test was
     * written first and passed against the defect, which is the only reason this one exists in this
     * shape.
     *
     * **The `#` is the whole of the discrimination — do not tidy it out of these names.** Measured
     * on sqlite-jdbc 3.53.4.0: `#` and `?` make the parser open a *truncated* name and leave a
     * stray 0-byte file, `%` fails `SQLITE_CANTOPEN`, and a **space needs no escaping at all**. So
     * trimming `"loopky apkg #dir"` to `"loopky apkg dir"` would leave a test that passes against
     * the defect, which is the exact failure this file exists to prevent.
     *
     * Not a Windows test, despite the fix being motivated by it: running `data.anki` on
     * `windows-latest` against the old form came back green, because a plain temp path there
     * carries none of the three characters that break it.
     */
    @Test
    fun `opens a collection whose path needs escaping`() {
        val dir = Files.createTempDirectory("loopky apkg #dir").toFile()
        val collection = File(dir, "my collection #1.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${collection.absolutePath}").use { db ->
            db.createStatement().use { it.executeUpdate("CREATE TABLE notes (id INTEGER PRIMARY KEY)") }
        }

        DriverManager.getConnection(sqliteReadOnlyUrl(collection)).use { db ->
            db.createStatement().use { statement ->
                val rs = statement.executeQuery("SELECT count(*) FROM notes")
                assertTrue(rs.next())
            }
        }
    }

    @Test
    fun `recognises a zip by its magic bytes`() {
        assertTrue(ApkgReader.canRead(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertTrue(!ApkgReader.canRead("not a zip".encodeToByteArray()))
    }

    /**
     * A file that is a zip but holds no collection is the reader's *own* failure to report, and it
     * must stay distinguishable from the one above — that is the whole point of [ApkgFailure].
     */
    @Test
    fun `a zip with no collection is reported as unsupported`() {
        val notAnApkg = File.createTempFile("loopky-empty", ".apkg").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("media"))
                zip.write("{}".encodeToByteArray())
                zip.closeEntry()
            }
        }

        val error = runCatching {
            kotlinx.coroutines.runBlocking {
                ApkgReader.readNotes(notAnApkg.absolutePath, null, ::unusedImage).getOrThrow()
            }
        }.exceptionOrNull()

        assertEquals(ApkgFailure.UnsupportedFormat, (error as? ApkgException)?.reason, "was $error")
    }

    /**
     * A minimal `collection.anki21` inside a zip.
     *
     * The `notes` table carries the columns Anki really has rather than only the two this reader
     * selects: `dominantNoteTypeId` reads `mid`, and a fixture missing it would exercise a
     * different path from a real export.
     */
    private fun writeApkg(vararg cards: Pair<String, String>): File {
        val collection = File.createTempFile("loopky-collection", ".anki21")
        DriverManager.getConnection("jdbc:sqlite:${collection.absolutePath}").use { db ->
            db.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE notes (id INTEGER PRIMARY KEY, guid TEXT, mid INTEGER, mod INTEGER, " +
                        "usn INTEGER, tags TEXT, flds TEXT, sfld TEXT, csum INTEGER, flags INTEGER, data TEXT)",
                )
            }
            cards.forEachIndexed { index, (front, back) ->
                db.prepareStatement(
                    "INSERT INTO notes (id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data) " +
                        "VALUES (?, ?, 1, 0, 0, '', ?, ?, 0, 0, '')",
                ).use { statement ->
                    statement.setLong(1, (index + 1).toLong())
                    statement.setString(2, "guid$index")
                    // Anki joins a note's fields with the ASCII unit separator.
                    statement.setString(3, "$front$ANKI_FIELD_SEPARATOR$back")
                    statement.setString(4, front)
                    statement.executeUpdate()
                }
            }
        }

        val apkg = File.createTempFile("loopky-deck", ".apkg")
        ZipOutputStream(apkg.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("collection.anki21"))
            zip.write(collection.readBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("media"))
            zip.write("{}".encodeToByteArray())
            zip.closeEntry()
        }
        collection.delete()
        return apkg
    }

    /** These fixtures carry no media, so a call here means the reader found one that is not there. */
    private suspend fun unusedImage(bytes: ByteArray, mime: String): DraftCardImage =
        error("no image expected: ${bytes.size} bytes of $mime")
}
