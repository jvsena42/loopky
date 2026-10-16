package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.data.anki.ApkgFieldMapping
import com.github.jvsena42.loopky.data.repository.impl.ImportRepositoryImpl
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `loopky import deck.apkg`, over real archives.
 *
 * The reader was built, tested and unreachable (#211): `ApkgReaderJvmTest` was the only thing that
 * ever called it, because there was no `.apkg` entry point on the CLI. These drive the entry point
 * instead — format detection, the headless field picker, and the accounting that has to reach the
 * `--json` envelope, which is most of the reason to do this on a terminal at all.
 */
class ApkgImportTest {

    // ---- format detection -------------------------------------------------------------------

    @Test
    fun `an apkg extension routes to the apkg reader even when the file is not a zip`() {
        val notAZip = tempFile("plainly-not-a-zip", ".apkg").apply { writeText("front\tback") }

        assertEquals(ImportFormat.Apkg, detectImportFormat(notAZip.path) { fileHeader(notAZip.path) })
    }

    /**
     * Content is the weaker of the two tests and still has to work: a deck downloaded as
     * `Spanish.zip`, or renamed, is the same archive.
     */
    @Test
    fun `a zip with no telling extension is still recognised`() {
        val renamed = ApkgFixture().note("hola", "hello").write().copyToPath(".bin")

        assertEquals(ImportFormat.Apkg, detectImportFormat(renamed.path) { fileHeader(renamed.path) })
    }

    @Test
    fun `an ordinary text file is text`() {
        val tsv = tempFile("cards", ".tsv").apply { writeText("hola\thello\n") }

        assertEquals(ImportFormat.Text, detectImportFormat(tsv.path) { fileHeader(tsv.path) })
    }

    @Test
    fun `an unreadable path has an empty header rather than throwing here`() {
        // The "no such file" report belongs to the command, which says which file. Throwing from
        // the sniff would report it as an internal failure instead.
        assertEquals(0, fileHeader("/no/such/file.apkg").size)
    }

    // ---- the headless field picker ----------------------------------------------------------

    @Test
    fun `a field is chosen by name, case-insensitively`() {
        val mapping = resolveFieldMapping(
            args("import", "d.apkg", "--front-field", "expression", "--back-field", "Meaning"),
            names = listOf("Id", "Expression", "Meaning"),
            chosen = ApkgFieldMapping(0, 1),
        )

        assertEquals(ApkgFieldMapping(frontOrd = 1, backOrd = 2), mapping)
    }

    /** 1-based, matching the "Field 1" label an unnamed field is shown under. */
    @Test
    fun `a field is chosen by its one-based number`() {
        val mapping = resolveFieldMapping(
            args("import", "d.apkg", "--front-field", "2", "--back-field", "3"),
            names = listOf("Id", "Expression", "Meaning"),
            chosen = ApkgFieldMapping(0, 1),
        )

        assertEquals(ApkgFieldMapping(frontOrd = 1, backOrd = 2), mapping)
    }

    /** A name beats a number, so a deck whose fields are literally called "1" is addressable. */
    @Test
    fun `a field named like a number is matched as a name first`() {
        val mapping = resolveFieldMapping(
            args("import", "d.apkg", "--front-field", "2"),
            names = listOf("2", "1", "Meaning"),
            chosen = ApkgFieldMapping(0, 2),
        )

        assertEquals(0, mapping.frontOrd)
    }

    /** "The front is right and the back is wrong" is the common half-correct case. */
    @Test
    fun `naming one field leaves the heuristic's choice for the other`() {
        val mapping = resolveFieldMapping(
            args("import", "d.apkg", "--back-field", "Notes"),
            names = listOf("Id", "Expression", "Notes"),
            chosen = ApkgFieldMapping(frontOrd = 1, backOrd = 0),
        )

        assertEquals(ApkgFieldMapping(frontOrd = 1, backOrd = 2), mapping)
    }

    @Test
    fun `naming the same field twice is a usage error, not a one-sided card`() {
        val error = usageError {
            resolveFieldMapping(
                args("import", "d.apkg", "--front-field", "1", "--back-field", "Expression"),
                names = listOf("Expression", "Meaning"),
                chosen = ApkgFieldMapping(0, 1),
            )
        }

        assertTrue("two different fields" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `an unknown field lists the ones the deck has`() {
        val error = usageError {
            resolveFieldMapping(
                args("import", "d.apkg", "--front-field", "Sentence"),
                names = listOf("Expression", "Meaning"),
                chosen = ApkgFieldMapping(0, 1),
            )
        }

        assertTrue("1 \"Expression\"" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("--dry-run" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a number past the last field is refused rather than clamped`() {
        usageError {
            resolveFieldMapping(
                args("import", "d.apkg", "--front-field", "9"),
                names = listOf("Expression", "Meaning"),
                chosen = ApkgFieldMapping(0, 1),
            )
        }
    }

    @Test
    fun `two fields with the same name are refused, with their numbers`() {
        val error = usageError {
            resolveFieldMapping(
                args("import", "d.apkg", "--front-field", "Extra"),
                names = listOf("Extra", "Meaning", "Extra"),
                chosen = ApkgFieldMapping(0, 1),
            )
        }

        assertTrue("1, 3" in error.message.orEmpty(), error.message.orEmpty())
    }

    // ---- the dry run, end to end over a real archive -----------------------------------------

    @Test
    fun `a dry run reports the fields with a sample of each, and writes nothing`() {
        val apkg = ApkgFixture()
            .fields("SentenceId", "Spanish", "English")
            .note("2528426", "Hola", "Hello")
            .note("2760065", "Adiós", "Goodbye")
            .write()

        val data = dryRun(apkg)

        val fields = data.obj("apkg").getValue("fields").jsonArray
        assertEquals(listOf(1, 2, 3), fields.map { it.jsonObject.int("index") })
        assertEquals(listOf("SentenceId", "Spanish", "English"), fields.map { it.jsonObject.string("name") })
        assertEquals(listOf("2528426", "Hola", "Hello"), fields.map { it.jsonObject.string("sample") })
        assertEquals(2, data.int("cards"))
        assertEquals("apkg", data.string("format"))
        assertTrue(data.bool("dry_run"))
    }

    /**
     * The heuristic, reported. 9000 Spanish Sentences imported 9,213 cards reading `2528426` →
     * `2760065` because its first two fields are database ids (#96); the point of putting the
     * mapping in the envelope is that an agent can see that happening before it publishes.
     */
    @Test
    fun `the chosen mapping skips a column of identifiers`() {
        val apkg = ApkgFixture()
            .fields("SentenceId", "Spanish", "English")
            .note("2528426", "Hola", "Hello")
            .note("2760065", "Adiós", "Goodbye")
            .write()

        val mapping = dryRun(apkg).obj("apkg").obj("mapping")

        assertEquals("Spanish", mapping.string("front_name"))
        assertEquals(2, mapping.int("front_index"))
        assertEquals("English", mapping.string("back_name"))
    }

    @Test
    fun `--front-field overrides the heuristic, and the cards follow it`() {
        val apkg = ApkgFixture()
            .fields("SentenceId", "Spanish", "English")
            .note("2528426", "Hola", "Hello")
            .write()

        val data = dryRun(apkg, "--front-field", "English", "--back-field", "Spanish")

        val mapping = data.obj("apkg").obj("mapping")
        assertEquals("English", mapping.string("front_name"))
        assertEquals("Spanish", mapping.string("back_name"))
        assertEquals(1, data.int("cards"))
    }

    /**
     * The accounting #96 exists for: notes that never became cards, reported by reason rather than
     * silently subtracted from a total nobody can reconcile.
     */
    @Test
    fun `dropped notes reach the envelope, by reason`() {
        val apkg = ApkgFixture()
            .fields("Front", "Back")
            .note("hola", "hello")
            .note("", "")
            .note("solo", "")
            .note("", "orphan")
            .write()

        val summary = dryRun(apkg).obj("apkg")

        val dropped = summary.obj("dropped")
        assertEquals(4, summary.int("note_count"))
        assertEquals(1, dropped.int("empty"))
        assertEquals(2, dropped.int("half_empty"))
        assertEquals(3, dropped.int("total"))
    }

    @Test
    fun `a reversed note type arrives as a suggestion`() {
        val apkg = ApkgFixture().fields("Front", "Back").templates(2).note("hola", "hello").write()

        assertTrue(dryRun(apkg).obj("apkg").bool("reversible"))
    }

    /**
     * The spend, before it is spent. This is the one import path that uploads bytes, and it
     * uploads them uncompressed — so the number has to be on screen while it is still a choice.
     */
    @Test
    fun `a picture is counted and its bytes reported`() {
        val blob = ByteArray(4_096) { it.toByte() }
        val apkg = ApkgFixture()
            .fields("Word", "Picture")
            .media("dog.jpg", blob)
            .note("perro", ApkgFixture.image("dog.jpg"))
            .write()

        val images = dryRun(apkg).obj("apkg").obj("images")

        assertEquals(1, images.int("imported"))
        assertEquals(0, images.int("skipped"))
        assertEquals(blob.size, images.int("bytes"))
    }

    /**
     * Reported, never adopted. A tag is a public record indexed network-wide (§7.7) and a
     * description is AnkiWeb boilerplate more often than not; this client writes neither on a deck
     * nobody asked it to.
     */
    @Test
    fun `note tags are suggested rather than applied`() {
        val apkg = ApkgFixture()
            .fields("Front", "Back")
            .note("hola", "hello", tags = "spanish")
            .note("adiós", "goodbye", tags = "spanish")
            .write()

        assertEquals(
            listOf("spanish"),
            dryRun(apkg).obj("apkg").getValue("suggested_tags").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /**
     * `parseBulkNotes` stamps `Separator.Tab` on a draft it never split. Reporting that would name
     * a rule that did not run, on the one format where "was this split how I meant?" is a real
     * question.
     */
    @Test
    fun `a structured source reports no separator`() {
        val apkg = ApkgFixture().fields("Front", "Back").note("hola", "hello").write()

        assertEquals("none", dryRun(apkg).string("separator"))
    }

    /**
     * The advice a string can give without asking any host is on the **verification channel**, not
     * only on stderr.
     *
     * It is the half the code calls more valuable — what a URL is *known* to be wrong about rather
     * than what a host answered this minute — and it was invisible to `--json` while
     * `--check-images` findings were not (#261 review, finding 2). It is a sibling array rather
     * than a row in `image_checks`, which is documented as what that opt-in flag found: this runs
     * whether or not the flag was passed.
     */
    @Test
    fun `static picture advice travels in the envelope, not only on stderr`() {
        val tsv = tempFile("cards", ".tsv").apply {
            writeText(
                "bandeira\tflag\thttps://upload.wikimedia.org/wikipedia/commons/0/03/Flag.svg\t\n" +
                    "gaivota\tgull\thttps://upload.wikimedia.org/wikipedia/commons/thumb/9/9a/G.jpg/800px-G.jpg\t\n",
            )
        }

        val advice = dryRun(tsv)["image_advice"]!!.jsonArray

        assertEquals(2, advice.size)
        assertEquals(
            listOf("Card 1 front image"),
            advice[0].jsonObject.getValue("where").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue("either app can decode" in advice[0].jsonObject.string("advice"))
        assertTrue("800px" in advice[1].jsonObject.string("advice"))
        // No --check-images here: the two arrays are independent, which is why they are two.
        assertEquals(0, dryRun(tsv)["image_checks"]!!.jsonArray.size)
    }

    /**
     * The pre-flight must not be laxer than the run it previews. `import --cover-url http://…`
     * exits 9; the same command with `--dry-run` exited 0 and said nothing, on the command whose
     * `--dry-run` is documented as the thing you run first (#261 review round 3).
     */
    @Test
    fun `a dry run refuses a cover the real run would refuse`() {
        val tsv = tempFile("cards", ".tsv").apply { writeText("hola\thello\nadiós\tgoodbye\n") }

        val error = runCatching { dryRun(tsv, "--cover-url", "http://example.test/cover.jpg") }
            .exceptionOrNull() as? CliError ?: fail("expected a CliError")

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue("cleartext" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a dry run reports what is knowably wrong with the cover it was given`() {
        val tsv = tempFile("cards", ".tsv").apply { writeText("hola\thello\nadiós\tgoodbye\n") }
        val svg = "https://upload.wikimedia.org/wikipedia/commons/0/03/Cover.svg"

        val advice = dryRun(tsv, "--cover-url", svg)["image_advice"]!!.jsonArray

        assertEquals(svg, advice.single().jsonObject.string("url"))
    }

    @Test
    fun `a file whose pictures are all fine carries no advice`() {
        val tsv = tempFile("cards", ".tsv").apply {
            writeText("a\tb\thttps://upload.wikimedia.org/wikipedia/commons/thumb/9/9a/G.jpg/500px-G.jpg\t\n")
        }

        assertEquals(0, dryRun(tsv)["image_advice"]!!.jsonArray.size)
    }

    /**
     * A four-column TSV goes through the same structured entry point as an `.apkg` and used to
     * report `"none"` for it — which reads as a parse failure on a file that parsed perfectly, on
     * exactly the run whose job is validating the file before a production write (#257, item 7).
     * It is split on tabs, so it says so.
     */
    @Test
    fun `a four-column TSV reports the separator its own reader used`() {
        val tsv = tempFile("cards", ".tsv").apply {
            writeText(
                "hola\thello\thttps://example.test/a.png\t\n" +
                    "adiós\tgoodbye\thttps://example.test/b.png\t\n",
            )
        }

        val data = dryRun(tsv)

        assertEquals(2, data.int("cards"))
        assertEquals("tab", data.string("separator"))
    }

    @Test
    fun `a dry run of a text file still works, and says it is text`() {
        val tsv = tempFile("cards", ".tsv").apply { writeText("hola\thello\nadiós\tgoodbye\n") }

        val data = dryRun(tsv)

        assertEquals("text", data.string("format"))
        assertEquals(2, data.int("cards"))
        assertEquals("tab", data.string("separator"))
        assertTrue(data.isNull("apkg"))
    }

    /** `--title` is what `--resume` matches on, so it stays mandatory for a real import — but a
     * preview is frequently what tells you what the deck should be called. */
    @Test
    fun `a dry run needs no title`() {
        val apkg = ApkgFixture().fields("Front", "Back").deck("Japanese Core").note("a", "b").write()

        val data = dryRun(apkg)

        assertTrue(data.isNull("title"))
        assertEquals("Japanese Core", data.obj("apkg").string("deck_name"))
    }

    // ---- failures that need different advice -------------------------------------------------

    /**
     * A zip with no readable collection is the zstd `collection.anki21b` case in practice, and the
     * advice is the point: all three `ApkgFailure` reasons share [ExitCode.BadInput], so the
     * message is the only thing that tells them apart.
     */
    @Test
    fun `an unreadable collection says how to re-export`() {
        val empty = tempFile("no-collection", ".apkg").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("media"))
                zip.write("{}".encodeToByteArray())
                zip.closeEntry()
            }
        }

        val error = importError(empty)

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue("collection.anki21b" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("Notes in Plain Text" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a file named apkg that is not a zip says so as an apkg failure`() {
        val notAZip = tempFile("truncated", ".apkg").apply { writeText("half a download") }

        val error = importError(notAZip)

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue("could not be opened as an .apkg" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a missing file is reported by name`() {
        val error = importError(File("/no/such/deck.apkg"))

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue("No such file" in error.message.orEmpty(), error.message.orEmpty())
    }

    // ---- flags that belong to the other format ------------------------------------------------

    @Test
    fun `--separator is refused for an apkg rather than ignored`() {
        val apkg = ApkgFixture().fields("Front", "Back").note("a", "b").write()

        val error = usageError { dryRun(apkg, "--separator", "tab") }

        assertTrue("--front-field" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `--front-field is refused for a text file rather than ignored`() {
        val tsv = tempFile("cards", ".tsv").apply { writeText("hola\thello\n") }

        val error = usageError { dryRun(tsv, "--front-field", "1") }

        assertTrue("--separator" in error.message.orEmpty(), error.message.orEmpty())
    }

    // ---- bytes, as a person reads them --------------------------------------------------------

    @Test
    fun `bytes are formatted in the decimal units a quota is quoted in`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("12 kB", formatBytes(12_000))
        assertEquals("4.2 MB", formatBytes(4_200_000))
        assertEquals("1.5 GB", formatBytes(1_500_000_000))
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun dryRun(file: File, vararg extra: String): JsonObject = runBlocking {
        importDryRun(Args.parse(arrayOf("import", file.path, "--dry-run", *extra)), ImportRepositoryImpl())
            .data.jsonObject
    }

    /** The failure a real `import` would raise, reached through the dry run's shared parse spine. */
    private fun importError(file: File): CliError = runBlocking {
        runCatching { importDryRun(Args.parse(arrayOf("import", file.path, "--dry-run")), ImportRepositoryImpl()) }
            .exceptionOrNull() as? CliError
            ?: error("expected a CliError for ${file.path}")
    }

    private fun usageError(block: () -> Unit): CliError {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is CliError, "expected a CliError, got $error")
        assertEquals(ExitCode.Usage, error.exitCode, error.message.orEmpty())
        return error
    }

    private fun args(vararg argv: String): Args = Args.parse(arrayOf(*argv))
}

private fun tempFile(prefix: String, suffix: String): File =
    File.createTempFile("loopky-test-$prefix", suffix).also { it.deleteOnExit() }

private fun File.copyToPath(suffix: String): File =
    tempFile("renamed", suffix).also { it.writeBytes(readBytes()) }

private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

/** `explicitNulls` is on, so an absent field is present and null rather than missing. */
private fun JsonObject.isNull(key: String): Boolean = getValue(key) is JsonNull
