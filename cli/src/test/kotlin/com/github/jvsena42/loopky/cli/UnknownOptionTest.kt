package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.requireImageCheckOptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A flag a command does not take is refused, **by name**.
 *
 * The parser accepts any `--long` it sees, so a flag carried over from a sibling command was
 * dropped on the floor and the command failed for a second, unrelated reason — followed by sixty
 * lines of manual. Nothing in that output said which flag was wrong (#257, item 5).
 */
class UnknownOptionTest {

    private fun parse(vararg argv: String) = Args.parse(arrayOf(*argv))

    /** The exact command from the issue. */
    @Test
    fun `a flag from a sibling command is named, and so is where it belongs`() {
        val error = assertFailsWith<CliError> {
            parse("import", "--dry-run", "--check-images", "--from-file", "deck.tsv").requireKnownOptions()
        }

        assertEquals(ExitCode.Usage, error.exitCode)
        val message = error.message.orEmpty()
        assertContains(message, "--from-file")
        assertContains(message, "import")
        assertContains(message, "deck create")
        // The substitution, spelled out: `import` takes its file as an operand.
        assertContains(message, "loopky import <file>")
    }

    /** And the message ends by saying what the command *does* take, which is what to act on. */
    @Test
    fun `the message lists the options the command has`() {
        val error = assertFailsWith<CliError> { parse("deck", "list", "--limit", "3").requireKnownOptions() }

        assertContains(error.message.orEmpty(), "--json")
        assertContains(error.message.orEmpty(), "card list")
    }

    /**
     * The derivation, and it is the one that matters after this change.
     *
     * An undeclared flag used to be *ignored*; it is now a hard exit 2. So a flag added to a
     * command's code without a matching table entry stops the command dead, and the guard against
     * that cannot be a hand-written argv list — the list is exactly what someone adding a flag
     * forgets (#261 review, finding 9). This reads the CLI's own source instead: every option name
     * any command actually asks [Args] for must be described somewhere in [cliCommands], the
     * globals or [UNOFFERED_SWITCHES].
     *
     * It cannot say a flag is on the *right* command — the test below does that by hand — but it
     * catches the fatal direction for free, and keeps catching it.
     */
    @Test
    fun `every option name the source reads is described somewhere`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "expected to find the CLI sources next to the test's working directory")
        val text = sources.joinToString("\n") { it.readText() }

        // `internal const val CHECK_IMAGES_FLAG = "check-images"` and friends, so a read spelled
        // as a constant is not invisible here.
        val constants = Regex("""const val (\w+) = "([a-z][a-z0-9-]*)"""")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }

        // `requireOption` too, and it is not covered by `option`: `\b` is case-sensitive, so
        // `requireOption("title")` matched nothing and a future `requireOption("newflag")` would
        // have passed this guard and then exited 2 at runtime.
        val accessors = "requireOption|options|option|has|flagOrNull|flag|positiveIntOrNull|positiveInt"
        val read = Regex("""\b(?:$accessors)\(\s*(?:"([^"$]+)"|(\w+))""")
            .findAll(text)
            .mapNotNull { match ->
                val literal = match.groupValues[1]
                if (literal.isNotEmpty()) literal else constants[match.groupValues[2]]
            }
            .toSet()

        val described = (cliCommands().flatMap { it.options } + GLOBAL_OPTIONS).mapTo(mutableSetOf()) { it.name } +
            UNOFFERED_SWITCHES
        assertEquals(
            emptySet(),
            read - described,
            "read from Args but not in CommandSurface.kt — requireKnownOptions now makes that exit 2",
        )
    }

    @Test
    fun `every flag a command reads is in the table`() {
        listOf(
            arrayOf("import", "f.tsv", "--title", "t", "--cover-emoji", "x", "--cover-url", "https://a.test/b.png"),
            arrayOf("import", "f.tsv", "--title", "t", "--resume", "--no-listen", "--no-reverse"),
            arrayOf("deck", "create", "--title", "t", "--dry-run", "--check-images-concurrency", "4"),
            arrayOf("card", "add", "d1", "--from-file", "f.tsv", "--dry-run"),
            arrayOf("card", "edit", "d1", "--from-file", "f.jsonl", "--check-images"),
            arrayOf("deck", "edit", "d1", "--clear-tags", "--clear-cover", "--no-speak"),
            arrayOf("card", "list", "d1", "--limit", "10", "--cursor", "0:1", "--has-image"),
            arrayOf("login", "--export", "--url-only", "--qr-out", "q.png", "--timeout", "30"),
            arrayOf("batch", "f.jsonl", "--stop-on-error"),
            arrayOf("update", "--check"),
        ).forEach { argv -> Args.parse(argv).requireKnownOptions() }
    }

    /** The globals are global: refusing one on a command would be the same bug pointing the other way. */
    @Test
    fun `the global options are accepted everywhere`() {
        parse("deck", "list", "--json", "--env", "staging", "--verbose", "--no-update-check").requireKnownOptions()
    }

    /**
     * `--yes` and `--force` are taken and ignored on purpose (see [UNOFFERED_SWITCHES]); refusing
     * them here would break a caller in the habit of passing one, which is what they exist for.
     */
    @Test
    fun `the switches this client accepts and ignores stay accepted`() {
        parse("deck", "delete", "d1", "--yes", "--force").requireKnownOptions()
    }

    /**
     * `--check-images-concurrency` is read only from behind `--check-images`, so on its own it was
     * accepted and silently ignored — and its value went unchecked with it (#261 review,
     * finding 3). Silently doing something other than what was asked is the failure the flag
     * exists to stop.
     */
    @Test
    fun `the image-check dial is refused where it can do nothing`() {
        val error = assertFailsWith<CliError> {
            parse("card", "add", "d1", "--from-file", "f.tsv", "--check-images-concurrency", "9")
                .requireImageCheckOptions()
        }

        assertEquals(ExitCode.Usage, error.exitCode)
        assertContains(error.message.orEmpty(), "--check-images")
    }

    @Test
    fun `an out-of-range dial is refused even before the probe would run`() {
        val error = assertFailsWith<CliError> {
            parse("import", "f.tsv", "--title", "t", "--check-images", "--check-images-concurrency", "99")
                .requireImageCheckOptions()
        }

        assertEquals(ExitCode.Usage, error.exitCode)
    }

    @Test
    fun `the pair together, and neither, are both fine`() {
        parse("import", "f.tsv", "--title", "t", "--check-images", "--check-images-concurrency", "4")
            .requireImageCheckOptions()
        parse("import", "f.tsv", "--title", "t", "--check-images").requireImageCheckOptions()
        parse("import", "f.tsv", "--title", "t").requireImageCheckOptions()
    }

    /** An unknown *verb* has a better message of its own, so this says nothing about it. */
    @Test
    fun `a command that is not in the table is left to the dispatcher`() {
        parse("teleport", "--nowhere", "x").requireKnownOptions()
    }
}
