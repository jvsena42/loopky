package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `--dry-run` on `deck create` and `card add`.
 *
 * It existed only on `import`, so pre-flighting a `deck create --from-file` meant running
 * `import --dry-run` over the same file — a **different** parser, and the two disagreed about a
 * well-formed four-column TSV (#257, item 8). Each command now stops just before its own write,
 * which is the only place an answer about that command can come from.
 *
 * Both need a session where they read the homeserver — is this id free, is this row already in
 * the deck. `deck create --dry-run` without `--id` asks nothing, so it runs without one (#367).
 */
class DryRunTest {

    private val session = Session(
        identity = PubkyIdentity(pubky = "pk:test", displayName = null, avatarUrl = null, bio = null),
        sessionSecret = "pk:test:cookie",
        homeserver = "hs",
        capabilities = listOf(Capability("/pub/loopky/:rw")),
    )

    private val missing = IllegalStateException("Request failed: 404 Not Found - pubky://x/manifest.json")

    private fun create(vararg argv: String) = Args.parse(arrayOf("deck", "create") + argv)

    private fun fileOf(rows: Int): String {
        val file = File.createTempFile("cards", ".tsv").also { it.deleteOnExit() }
        file.writeText((1..rows).joinToString("\n") { "front $it\tback $it" })
        return file.absolutePath
    }

    private fun addArgs(path: String, vararg extra: String) =
        Args.parse(arrayOf("card", "add", "d1", "--from-file", path, *extra))

    /**
     * `--dry-run` through this command's own path.
     *
     * The pre-flight for a `deck create --from-file` was `import --dry-run` over the same file —
     * a **different** parser, so it could not answer what this command would do with it (#257,
     * item 8). The id check runs, so this needs a session where `import --dry-run` does not.
     */
    @Test
    fun `a dry run reports the deck it would publish and writes nothing`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "mine00000001"), readFails = missing)

        val result = deckCreate(
            create("--title", "Capitais", "--id", "mine00000001", "--dry-run", "--tag", "geografia"),
            decks,
            session,
            {},
            {},
        )

        val data = result.data.jsonObject
        assertEquals(emptyList(), decks.published)
        assertEquals("true", data.getValue("dry_run").jsonPrimitive.content)
        // `true` for a free id, which is what separates this from the branch below: the question
        // `--id X --if-not-exists --dry-run` is asked is whether the id is free, and reporting
        // `false` for both previews left nothing able to answer it.
        assertEquals("true", data.getValue("created").jsonPrimitive.content)
        assertEquals("Capitais", data.getValue("deck").jsonObject.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun `a dry run without --id checks the file with no session and no read`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "unused"), readFails = missing)

        val result = deckCreate(
            create("--title", "Capitais", "--from-file", fileOf(3), "--dry-run"),
            decks,
            session = null,
            {},
            {},
        )

        val data = result.data.jsonObject
        assertEquals("true", data.getValue("created").jsonPrimitive.content)
        assertEquals("false", data.getValue("id_checked").jsonPrimitive.content)
        assertEquals("3", data.getValue("deck").jsonObject.getValue("card_count").jsonPrimitive.content)
        assertTrue(decks.fetchRemoteCalls.isEmpty() && decks.syncCalls.isEmpty())
        assertEquals(emptyList(), decks.published)
    }

    @Test
    fun `a dry run without a session still refuses a bad card file`() = runBlocking {
        val file = File.createTempFile("cards", ".tsv").also { it.deleteOnExit() }
        file.writeText("front only\t\n")

        val error = assertFailsWith<CliError> {
            deckCreate(create("--title", "T", "--from-file", file.absolutePath, "--dry-run"), FakeDeckRepository(testDeck()), null, {}, {})
        }

        assertEquals(ExitCode.BadInput, error.exitCode)
    }

    /** An id that is taken is still refused on a dry run — that is the answer being asked for. */
    @Test
    fun `a dry run over a taken id is refused, not previewed`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "mine00000001", cardCount = 40))

        val error = assertFailsWith<CliError> {
            deckCreate(create("--title", "T", "--id", "mine00000001", "--dry-run"), decks, session, {}, {})
        }

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertEquals(emptyList(), decks.published)
    }

    /**
     * And with `--if-not-exists` over a **taken** id it reports the deck that is there, still
     * marked a dry run — `created = false`, the one bit that distinguishes it from the preview
     * above. All four combinations of the two fields are distinct.
     */
    @Test
    fun `a dry run that finds the deck says both things`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "mine00000001", cardCount = 40))

        val result = deckCreate(
            create("--title", "T", "--id", "mine00000001", "--if-not-exists", "--dry-run"),
            decks,
            session,
            {},
            {},
        )

        assertEquals("true", result.data.jsonObject.getValue("dry_run").jsonPrimitive.content)
        assertEquals("false", result.data.jsonObject.getValue("created").jsonPrimitive.content)
    }

    /**
     * `--dry-run` on the command that will actually run it. Pre-flighting a `card add` through
     * `import --dry-run` went through a different parser and could not answer this (#257, item 8).
     */
    @Test
    fun `a dry run plans the whole file and writes nothing`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 4))

        val json = cardAdd(addArgs(fileOf(250), "--dry-run"), decks, FakeCardRepository()).data.jsonObject

        assertEquals("true", json.getValue("dry_run").jsonPrimitive.content)
        assertEquals("250", json.getValue("written").jsonPrimitive.content)
        assertEquals(emptyList(), decks.appendBatches)
        assertEquals(emptyList(), decks.upsertAttempts)
    }

    /** And it is still the real path: a row already in the deck is deduped, not counted as new. */
    @Test
    fun `a dry run dedupes against the deck it read`() = runBlocking {
        val path = fileOf(3)
        val decks = FakeDeckRepository(testDeck(cardCount = 1))
        val existing = readCardFile(path) { }.take(1).map { it.toCard("d1", 0L, 0) }

        val json = cardAdd(addArgs(path, "--dry-run"), decks, FakeCardRepository(existing)).data.jsonObject

        assertEquals("2", json.getValue("written").jsonPrimitive.content)
        assertEquals("1", json.getValue("skipped").jsonPrimitive.content)
        assertTrue(decks.appendBatches.isEmpty())
    }
}
