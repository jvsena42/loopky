package com.github.jvsena42.loopky.cli.commands

import java.io.File
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A real `.apkg` — a real zip around a real SQLite collection — built in a temp directory.
 *
 * A fixture with teeth rather than a fake reader. The whole point of `loopky import deck.apkg` is
 * that it drives the *shipped* path: `java.util.zip`, the JDBC driver, the shared queries in
 * `ApkgCollection.kt`. A test that stubbed `ApkgReader` would have passed against the JDBC URL
 * that was never interpolated — every desktop import failing at "unable to open database file",
 * reported as *"That .apkg has no readable collection"* — which is the bug `ApkgReaderJvmTest` was
 * written for. This is that test's shape, one layer up, at the command.
 *
 * Only the columns the reader actually selects are populated, plus `mid`, which decides which note
 * type is dominant and therefore which field names and template count get read.
 */
internal class ApkgFixture {

    /** Each note's fields in ordinal order, and its space-separated Anki tags. */
    private val notes = mutableListOf<Pair<List<String>, String>>()
    private val media = linkedMapOf<String, ByteArray>()
    private var fieldNames: List<String> = emptyList()
    private var templates = 1
    private var deckName: String? = null

    fun note(vararg fields: String, tags: String = "") = apply { notes += fields.toList() to tags }

    fun fields(vararg names: String) = apply { fieldNames = names.toList() }

    /** Two or more templates is what `ApkgImport.reversible` reads as "drilled both ways". */
    fun templates(count: Int) = apply { templates = count }

    fun deck(name: String) = apply { deckName = name }

    /** A blob the archive holds under a numbered entry, reachable as `<img src="[name]">`. */
    fun media(name: String, bytes: ByteArray) = apply { media[name] = bytes }

    fun write(): File {
        val collection = File.createTempFile("loopky-test-collection", ".anki21").also { it.deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${collection.absolutePath}").use { db ->
            db.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE notes (id INTEGER PRIMARY KEY, guid TEXT, mid INTEGER, mod INTEGER, " +
                        "usn INTEGER, tags TEXT, flds TEXT, sfld TEXT, csum INTEGER, flags INTEGER, data TEXT)",
                )
                statement.executeUpdate("CREATE TABLE fields (ntid INTEGER, ord INTEGER, name TEXT)")
                statement.executeUpdate("CREATE TABLE templates (ntid INTEGER, ord INTEGER, name TEXT)")
                statement.executeUpdate("CREATE TABLE decks (id INTEGER PRIMARY KEY, name TEXT, kind BLOB)")

                fieldNames.forEachIndexed { ord, name ->
                    statement.executeUpdate(
                        "INSERT INTO fields (ntid, ord, name) VALUES ($NOTE_TYPE_ID, $ord, '$name')",
                    )
                }
                repeat(templates) { ord ->
                    statement.executeUpdate(
                        "INSERT INTO templates (ntid, ord, name) VALUES ($NOTE_TYPE_ID, $ord, 'Card ${ord + 1}')",
                    )
                }
                // id 1 is Anki's Default deck, which `readModernDeck` skips.
                deckName?.let {
                    statement.executeUpdate("INSERT INTO decks (id, name, kind) VALUES (2, '$it', NULL)")
                }
            }
            notes.forEachIndexed { index, (fields, tags) ->
                db.prepareStatement(
                    "INSERT INTO notes (id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data) " +
                        "VALUES (?, ?, $NOTE_TYPE_ID, 0, 0, ?, ?, ?, 0, 0, '')",
                ).use { statement ->
                    statement.setLong(1, (index + 1).toLong())
                    statement.setString(2, "guid$index")
                    statement.setString(3, tags)
                    // Anki joins a note's fields with the ASCII unit separator (0x1F).
                    statement.setString(4, fields.joinToString(FIELD_SEPARATOR))
                    statement.setString(5, fields.firstOrNull().orEmpty())
                    statement.executeUpdate()
                }
            }
        }

        val apkg = File.createTempFile("loopky-test-deck", ".apkg").also { it.deleteOnExit() }
        ZipOutputStream(apkg.outputStream()).use { zip ->
            zip.entry("collection.anki21", collection.readBytes())
            // The media manifest maps a numbered entry to the filename an <img src> names, which
            // is why `MediaIndex` reads it backwards.
            zip.entry(
                "media",
                media.keys
                    .mapIndexed { index, name -> "\"$index\":\"$name\"" }
                    .joinToString(",", "{", "}")
                    .encodeToByteArray(),
            )
            media.values.forEachIndexed { index, bytes -> zip.entry("$index", bytes) }
        }
        return apkg
    }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    internal companion object {
        /** Every note in a fixture shares one note type, so it is always the dominant one. */
        const val NOTE_TYPE_ID = 1

        /**
         * Anki's field separator, 0x1F, written as an escape rather than as the byte.
         * A literal control character in source makes the file binary as far as `grep` is
         * concerned, which `Cards.kt` records having learned the hard way.
         */
        const val FIELD_SEPARATOR = "\u001F"

        /** A field that is *nothing but* a picture — the only shape the reader attaches media for. */
        fun image(name: String): String = """<img src="$name">"""
    }
}
