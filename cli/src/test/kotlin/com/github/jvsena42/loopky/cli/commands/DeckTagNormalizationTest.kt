package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.FakeMediaRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.data.repository.impl.ImportRepositoryImpl
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.Tag
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `--tag` is folded to one canonical label on every command that takes it, and the envelope says
 * so (#479). `deck edit --tag café` on one deck and `--tag cafe` on another used to leave two decks
 * about one topic on two shelves of the index, with both commands reporting exactly what they were
 * sent — so these assert on what each path *wrote* and on what it *told the caller*.
 */
class DeckTagNormalizationTest {

    @Test
    fun `deck create stores the folded label and reports the fold`() = runBlocking {
        val decks = capturingDecks()

        val result = deckCreate(
            args("deck", "create", "--title", "Capitais", "--tag", "Café", "--tag", "foo bar", "--tag", "plain"),
            decks,
            testSession(),
        ) {}

        assertEquals(listOf(Tag("cafe"), Tag("foo-bar"), Tag("plain")), publishedDeck().tags)
        assertEquals(listOf("Café" to "cafe", "foo bar" to "foo-bar"), result.folds())
        assertTrue(result.text.contains("Café -> cafe"), result.text)
    }

    @Test
    fun `two spellings of one tag are one tag`() = runBlocking {
        val decks = capturingDecks()

        deckCreate(args("deck", "create", "--title", "T", "--tag", "café", "--tag", "CAFE", "--tag", "cafe"), decks, testSession()) {}

        assertEquals(listOf(Tag("cafe")), publishedDeck().tags)
    }

    @Test
    fun `a tag given in its stored spelling reports no fold`() = runBlocking {
        val result =
            deckCreate(args("deck", "create", "--title", "T", "--tag", "cafe"), capturingDecks(), testSession()) {}

        assertEquals(emptyList(), result.folds())
    }

    /** The pre-flight has to say it too: it is where an agent finds out before anything is public. */
    @Test
    fun `a dry run reports the fold and writes nothing`() = runBlocking {
        val decks = capturingDecks()

        val result = deckCreate(
            args("deck", "create", "--title", "T", "--tag", "Bioquímica", "--dry-run"),
            decks,
            session = null,
        ) {}

        assertEquals(listOf("Bioquímica" to "bioquimica"), result.folds())
        assertEquals(null, captured)
    }

    @Test
    fun `deck edit folds the tags it is given`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "d1"))

        val result = deckEdit(args("deck", "edit", "d1", "--tag", "café"), decks)

        assertEquals(listOf(Tag("cafe")), decks.metadataWrites.single().tags)
        assertEquals(listOf("café" to "cafe"), result.folds())
    }

    /** The repro in #479: the second deck must land on the first one's label, not beside it. */
    @Test
    fun `the accented and the plain spelling give two decks the same tag`() = runBlocking {
        val accented = FakeDeckRepository(testDeck(id = "d1"))
        val plain = FakeDeckRepository(testDeck(id = "d2"))

        deckEdit(args("deck", "edit", "d1", "--tag", "café"), accented)
        deckEdit(args("deck", "edit", "d2", "--tag", "cafe"), plain)

        assertEquals(accented.metadataWrites.single().tags, plain.metadataWrites.single().tags)
    }

    @Test
    fun `an edit that is not about tags folds the ones a deck already carried`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "d1").copy(tags = listOf(Tag("café"), Tag("verbos"))))

        val result = deckEdit(args("deck", "edit", "d1", "--title", "Renamed"), decks)

        assertEquals(listOf(Tag("cafe"), Tag("verbos")), decks.metadataWrites.single().tags)
        assertEquals(listOf("café" to "cafe"), result.folds())
        assertTrue(result.text.contains("tags"), result.text)
    }

    @Test
    fun `a deck whose tags are already folded is not rewritten for them`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "d1").copy(tags = listOf(Tag("cafe"))))

        val result = deckEdit(args("deck", "edit", "d1", "--tag", "Café"), decks)

        assertEquals(emptyList(), decks.metadataWrites)
        // Still said: "no change" on its own would read as `Café` being what the deck holds.
        assertEquals(listOf("Café" to "cafe"), result.folds())
    }

    @Test
    fun `import folds its tags and reports the fold`() {
        val decks = capturingDecks()

        val result = runImport(decks, "--tag", "Geografía")

        assertEquals(listOf(Tag("geografia")), publishedDeck().tags)
        assertEquals(listOf("Geografía" to "geografia"), result.folds())
    }

    @Test
    fun `import --dry-run reports the fold`() = runBlocking {
        val result = importDryRun(
            Args.parse(arrayOf("import", cardFile().path, "--dry-run", "--tag", "Geografía")),
            ImportRepositoryImpl(),
            onNote = {},
        )

        assertEquals(listOf("Geografía" to "geografia"), result.folds())
    }

    @Test
    fun `an emoji tag is stored as given and reports no fold`() = runBlocking {
        val decks = capturingDecks()

        val result = deckCreate(
            args("deck", "create", "--title", "T", "--tag", "🇧🇷", "--tag", "👨‍👩‍👧‍👦", "--tag", "Café ☕", "--tag", "🔥".repeat(20)),
            decks,
            testSession(),
        ) {}

        assertEquals(
            listOf(Tag("🇧🇷"), Tag("👨‍👩‍👧‍👦"), Tag("cafe-☕"), Tag("🔥".repeat(20))),
            publishedDeck().tags,
        )
        assertEquals(listOf("Café ☕" to "cafe-☕"), result.folds())
    }

    /** Refused, not dropped: five tags asked for and four stored with exit 0 is a thing nobody checks. */
    @Test
    fun `a tag past the limit once folded is bad input`() = runBlocking {
        val decks = capturingDecks()

        val error = assertFailsWith<CliError> {
            // 20 characters as typed, 21 once `ß` is stored as `ss`.
            deckCreate(args("deck", "create", "--title", "T", "--tag", "a".repeat(19) + "ß"), decks, testSession()) {}
        }

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertEquals(null, captured)
    }

    @Test
    fun `a tag that folds into the reserved namespace is bad input`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "d1"))

        val error = assertFailsWith<CliError> { deckEdit(args("deck", "edit", "d1", "--tag", "Loopky Deck"), decks) }

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertEquals(emptyList(), decks.metadataWrites)
    }

    // ---- harness --------------------------------------------------------------------------

    private fun args(vararg argv: String) = Args.parse(arrayOf(*argv))

    private fun CommandResult.folds(): List<Pair<String, String>> =
        data.jsonObject.getValue("tags_normalized").jsonArray.map {
            val fold = it.jsonObject
            fold.getValue("from").jsonPrimitive.content to fold.getValue("to").jsonPrimitive.content
        }

    private var captured: Deck? = null

    private fun capturingDecks() = FakeDeckRepository(
        testDeck(),
        onPublish = { deck, cards ->
            captured = deck
            Result.success(deck.copy(cardCount = cards.size))
        },
    )

    private fun publishedDeck(): Deck = requireNotNull(captured) { "publish was never called" }

    private fun cardFile(): File = File.createTempFile("loopky-tag-fold", ".tsv").apply {
        deleteOnExit()
        writeText("hola\thello\nadios\tbye\ngracias\tthanks\n")
    }

    private fun runImport(decks: FakeDeckRepository, vararg extra: String) = runBlocking {
        import(
            args = Args.parse(arrayOf("import", cardFile().path, "--title", "Test import", *extra)),
            imports = ImportRepositoryImpl(),
            decks = decks,
            cards = FakeCardRepository(),
            media = FakeMediaRepository(),
            session = testSession(),
            onProgress = {},
            onNote = {},
        )
    }

    private fun testSession(): Session = Session(
        identity = PubkyIdentity(pubky = "pk:test", displayName = null, avatarUrl = null, bio = null),
        sessionSecret = "secret",
        capabilities = listOf(Capability("/pub/loopky/:rw")),
        homeserver = "hs:test",
    )
}
