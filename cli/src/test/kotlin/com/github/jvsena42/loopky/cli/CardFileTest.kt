package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.readCardFile
import com.github.jvsena42.loopky.cli.commands.requireBothSides
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardFileTest {

    private fun write(name: String, body: String): String =
        File.createTempFile("loopky-cards", name).apply { writeText(body) }.absolutePath

    @Test
    fun `reads two column tsv`() {
        val rows = readCardFile(write(".tsv", "hola\thello\nadios\tgoodbye\n"))
        assertEquals(2, rows.size)
        assertEquals("hola", rows[0].front)
        assertEquals("goodbye", rows[1].back)
        assertNull(rows[0].frontImageUrl)
    }

    /**
     * The columns the text formats cannot express. Neither `.apkg` nor TSV-through-the-paste-parser
     * can say "this side has text *and* a picture" — they carry an image only when a field is
     * nothing but that image — which is why every one of ~190 pictures went through the card
     * editor by hand (#54, finding 3).
     */
    @Test
    fun `reads image columns`() {
        val rows = readCardFile(
            write(".tsv", "Brasília\tCapital\thttps://example.test/a.jpg\thttps://example.test/b.jpg\n"),
        )
        assertEquals("https://example.test/a.jpg", rows.single().frontImageUrl)
        assertEquals("https://example.test/b.jpg", rows.single().backImageUrl)
    }

    @Test
    fun `an empty image column is absent rather than blank`() {
        val rows = readCardFile(write(".tsv", "hola\thello\t\t\n"))
        assertNull(rows.single().frontImageUrl)
        assertNull(rows.single().backImageUrl)
    }

    /**
     * The shape `card list --json` emits, fed straight back. The two shapes disagreeing is what
     * made "has this row already been applied?" a shape check plus a percent-decode, and getting
     * it wrong rewrote all 665 rows on every pass (#229, item 3).
     */
    @Test
    fun `reads a row in the shape card list --json emits`() {
        val rows = readCardFile(
            write(
                ".jsonl",
                """{"id":"c1","deck_id":"d1","ord":1000,"updated_at":7,""" +
                    """"front":{"text":"Brasília","image":{"url":"https://example.test/a.jpg",""" +
                    """"sha256":null,"mime":"image/jpeg"}},"back":{"text":"Capital","image":null}}""" + "\n",
            ),
        )
        val row = rows.single()
        assertEquals("c1", row.id)
        assertEquals("Brasília", row.front)
        assertEquals("https://example.test/a.jpg", row.frontImageUrl)
        assertEquals("Capital", row.back)
        // An explicit null image is "clear it", not "leave it": card list writes explicit nulls,
        // so feeding its output back has to mean exactly what it read.
        assertEquals("", row.backImageUrl)
    }

    /** A key that is absent still means "leave this alone", in either shape. */
    @Test
    fun `an omitted key in a stored-shape side leaves that field unchanged`() {
        val rows =
            readCardFile(write(".jsonl", """{"id":"c1","front":{"image":{"url":"https://x.test/a.jpg"}}}""" + "\n"))
        val row = rows.single()
        assertNull(row.front)
        assertNull(row.back)
        assertNull(row.backImageUrl)
        assertEquals("https://x.test/a.jpg", row.frontImageUrl)
    }

    /**
     * A blob picture has no URL a card file can carry, so it is left alone rather than cleared —
     * otherwise round-tripping an .apkg-imported deck through `card list` strips every picture.
     */
    @Test
    fun `a blob image round-trips as unchanged and is reported once`() {
        val notes = mutableListOf<String>()
        val rows = readCardFile(
            write(
                ".jsonl",
                """{"id":"c1","front":{"text":"a","image":{"url":null,"sha256":"ab12"}},"back":{"text":"b"}}""" + "\n",
            ),
            onNote = notes::add,
        )
        assertNull(rows.single().frontImageUrl)
        assertTrue(notes.any { "blob" in it }, "the caller has to be told those pictures were skipped")
    }

    @Test
    fun `the two shapes may be mixed within a row`() {
        val rows = readCardFile(
            write(".jsonl", """{"id":"c1","front":{"text":"a"},"back":"b","back_image_url":"https://x.test/b.jpg"}""" + "\n"),
        )
        assertEquals("a", rows.single().front)
        assertEquals("b", rows.single().back)
        assertEquals("https://x.test/b.jpg", rows.single().backImageUrl)
    }

    @Test
    fun `skips blank lines and comments so a generated file can carry a header`() {
        val rows = readCardFile(write(".tsv", "# front\tback\n\nhola\thello\n\n"))
        assertEquals(1, rows.size)
    }

    /** Non-ASCII in, exactly as given — the motivation `adb shell input text` could not meet. */
    @Test
    fun `round-trips accented text byte for byte`() {
        val rows = readCardFile(write(".tsv", "Sub-ecossistemas\tSub-ecosystems\nAçaí\tAçaí palm\n"))
        assertEquals("Sub-ecossistemas", rows[0].front)
        assertEquals("Açaí", rows[1].front)
    }

    @Test
    fun `reads jsonl by extension`() {
        val rows = readCardFile(
            write(".jsonl", """{"id":"c1","front":"hola","back":"hello"}""" + "\n"),
        )
        assertEquals("c1", rows.single().id)
        assertEquals("hola", rows.single().front)
    }

    /** A caller that has to remember `--jsonl` will one day not, so content decides too. */
    @Test
    fun `reads jsonl by content when the extension does not say`() {
        val rows = readCardFile(write(".txt", """{"front":"hola","back":"hello"}""" + "\n"))
        assertEquals("hola", rows.single().front)
    }

    @Test
    fun `a jsonl side may hold a tab, which is what the format is for`() {
        val rows = readCardFile(write(".jsonl", """{"front":"a\tb","back":"c"}""" + "\n"))
        assertEquals("a\tb", rows.single().front)
    }

    /**
     * A file that produced fewer cards than it has lines is exactly the loss `--json` exists to
     * show, so a row with nothing to write is refused rather than skipped.
     *
     * Reachable through JSONL, which is where an object can name a card and then say nothing about
     * it — `card edit --from-file` with an id and no fields changes nothing and should say so. A
     * TSV line cannot get here: a line with no content is blank, and blank lines are skipped.
     */
    @Test
    fun `a row with nothing on either side is an error, not a silent skip`() {
        val error = assertFailsWith<CliError> {
            readCardFile(write(".jsonl", """{"id":"c1"}""" + "\n"))
        }
        assertEquals(ExitCode.BadInput, error.exitCode)
    }

    /**
     * `publish` `require`s both sides too, but that throws an `IllegalArgumentException` no
     * classifier recognises — so a blank column in the user's own file would reach them as exit 1
     * "internal" plus a Kotlin assertion message.
     */
    @Test
    fun `a half-empty row is bad input, and the message names it`() {
        val rows = readCardFile(write(".tsv", "hola\thello\nadios\t\n"))
        val error = assertFailsWith<CliError> { rows.requireBothSides() }
        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue(error.message.orEmpty().contains("Row 2"), error.message.orEmpty())
    }

    /** A picture counts as a side — that is the whole point of the image columns. */
    @Test
    fun `an image alone is a side`() {
        val rows = readCardFile(write(".tsv", "hola\t\t\thttps://example.test/b.jpg\n"))
        assertEquals(rows, rows.requireBothSides())
    }

    /**
     * Every app-side constructor of a remote image ref gets its URL from a picker; this is the
     * first path where an arbitrary string reaches one. Unchecked, a 3-column Anki export
     * (Front / Back / Example sentence) publishes every card with `url = "una manzana roja"`,
     * both apps try to load prose as a picture, the column's real content is lost, and `--json`
     * reports success.
     */
    @Test
    fun `an image column that is not a URL is refused`() {
        val error = assertFailsWith<CliError> {
            readCardFile(write(".tsv", "una manzana\ta red apple\tuna manzana roja\n"))
        }
        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue(error.message.orEmpty().contains("https:// URL"), error.message.orEmpty())
    }

    /** `#` **followed by whitespace**. A card whose front is `#1 ranked` is a card, not a comment. */
    @Test
    fun `a hash without a space is a card, not a comment`() {
        val rows = readCardFile(write(".tsv", "# front\tback\n#1 ranked\tprimeiro\n"))
        assertEquals(1, rows.size)
        assertEquals("#1 ranked", rows.single().front)
    }

    @Test
    fun `a missing file is bad input rather than a crash`() {
        val error = assertFailsWith<CliError> { readCardFile("/no/such/file.tsv") }
        assertEquals(ExitCode.BadInput, error.exitCode)
    }

    @Test
    fun `an empty file is refused`() {
        val error = assertFailsWith<CliError> { readCardFile(write(".tsv", "\n\n")) }
        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue(error.message.orEmpty().contains("no cards"))
    }
}
