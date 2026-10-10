package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.data.repository.impl.ImportRepositoryImpl
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The card-writing rules the CLI now checks itself, where they used to be prose in the agent
 * skill: a file breaking five of them, with every study mode on and half a language pair,
 * published with exit 0 and nothing said.
 */
class CardAdviceTest {

    // ---- card advice ----------------------------------------------------------------------

    @Test
    fun `deck create names the cards that break a rule, in json and on stderr`() = runBlocking {
        val notes = mutableListOf<String>()
        val file = cardFile("casa\thouse", "casa\thome", "ou\teither / or", "começar (start)\tstart")

        val result = dryRun(file, onNote = notes::add)

        assertEquals(
            listOf(
                "duplicate_front" to listOf("Line 1", "Line 2"),
                "aside_holds_answer" to listOf("Line 4"),
                "alternatives" to listOf("Line 3"),
            ),
            result.cardAdvice(),
        )
        assertTrue(notes.any { it.contains("Line 1, Line 2") }, notes.toString())
    }

    @Test
    fun `the reverse and graded rules follow the deck's own flags`() = runBlocking {
        val file = cardFile("banco\tbank", "margem\tbank", "silêncio\t—")

        val plain = dryRun(file)
        val strict = dryRun(file, "--reverse", "--type")

        assertEquals(emptyList(), plain.cardAdvice())
        assertEquals(
            listOf("duplicate_back" to listOf("Line 1", "Line 2"), "nothing_to_grade" to listOf("Line 3")),
            strict.cardAdvice(),
        )
    }

    /** Advice never stops a publish: each rule has honest exceptions. */
    @Test
    fun `a file with findings is still published`() = runBlocking {
        var published = 0
        val decks = FakeDeckRepository(
            testDeck(),
            onPublish = { deck, cards ->
                published = cards.size
                Result.success(deck)
            },
        )

        val result = deckCreate(create("--from-file", cardFile("casa\thouse", "casa\thome")), decks, session(), {}) {}

        assertEquals(2, published)
        assertEquals(listOf("duplicate_front" to listOf("Line 1", "Line 2")), result.cardAdvice())
    }

    @Test
    fun `card add checks new cards against the deck and leaves the deck's own findings out`() = runBlocking {
        val existing = listOf(card("e1", "casa", "house"), card("e2", "gato", "cat"), card("e3", "gato", "tomcat"))
        val file = cardFile("casa\thome", "rua\tstreet")

        val result = cardAdd(
            Args.parse(arrayOf("card", "add", "d1", "--from-file", file, "--dry-run")),
            FakeDeckRepository(testDeck(cardCount = 3)),
            FakeCardRepository(existing),
            {},
        )

        // The two `gato` cards disagree as well, and nobody asked about those.
        assertEquals(listOf("duplicate_front" to listOf("card e1", "Line 1")), result.cardAdvice())
    }

    /** The picture advice for the same file says `Line N`, and an agent edits the line it is told. */
    @Test
    fun `a finding names the line of the file, comments and blank lines counted`() = runBlocking {
        val file = cardFile("# Spanish, week 1", "", "casa\thouse", "", "casa\thome")

        assertEquals(listOf("duplicate_front" to listOf("Line 3", "Line 5")), dryRun(file).cardAdvice())
    }

    @Test
    fun `a card given as flags is named by position, there being no line`() = runBlocking {
        val result = cardAdd(
            Args.parse(arrayOf("card", "add", "d1", "--front", "casa", "--back", "home", "--dry-run")),
            FakeDeckRepository(testDeck(cardCount = 1)),
            FakeCardRepository(listOf(card("e1", "casa", "house"))),
            {},
        )

        assertEquals(listOf("duplicate_front" to listOf("card e1", "Card 1")), result.cardAdvice())
    }

    @Test
    fun `symbols and emoji keep prompts apart`() = runBlocking {
        val file = cardFile("🇧🇷\tBrazil", "🇫🇷\tFrance", "C++\tcompiled", "C#\tmanaged", "कल\ttomorrow", "काल\ttime")

        assertEquals(emptyList(), dryRun(file, "--reverse").cardAdvice())
    }

    @Test
    fun `import reports the same findings before anything is uploaded`() = runBlocking {
        val file = cardFile("casa\thouse", "casa\thome")

        val result = importDryRun(Args.parse(arrayOf("import", file, "--dry-run")), ImportRepositoryImpl(), onNote = {})

        assertEquals(listOf("duplicate_front" to listOf("Card 1", "Card 2")), result.cardAdvice())
    }

    // ---- composition ----------------------------------------------------------------------

    @Test
    fun `a dry run reports words and phrases per stretch`() = runBlocking {
        val rows = List(100) { "p$it\tword" } + List(50) { "f$it\ta whole phrase" }

        val result = dryRun(cardFile(*rows.toTypedArray()))

        val stretches = result.data.jsonObject.getValue("composition").jsonArray
            .map { it.jsonObject.ints("from", "to", "words", "phrases") }
        assertEquals(listOf(listOf(1, 100, 100, 0), listOf(101, 150, 0, 50)), stretches)
    }

    // ---- listen and speak need the pair ---------------------------------------------------

    @Test
    fun `deck create refuses listen or speak without both languages`() = runBlocking {
        val error = assertFailsWith<CliError> {
            deckCreate(create("--listen", "--front-lang", "en-US"), FakeDeckRepository(testDeck()), session(), {}) {}
        }

        assertEquals(ExitCode.Usage, error.exitCode)
        assertTrue(error.message.orEmpty().contains("--back-lang"), error.message)
    }

    /** An empty value is not a language: it reached the manifest as one and counted as declared. */
    @Test
    fun `a blank language is no language`() = runBlocking {
        listOf(arrayOf("--front-lang", "", "--back-lang", ""), arrayOf("--front-lang", "  ", "--back-lang", "es-ES")).forEach {
            assertFailsWith<CliError> {
                deckCreate(create("--listen", "--dry-run", *it), FakeDeckRepository(testDeck()), null, {}) {}
            }
        }
        val published = deckCreate(
            create("--front-lang", "", "--dry-run"),
            FakeDeckRepository(testDeck()),
            null,
            {},
        ) {}

        assertEquals("null", published.data.jsonObject.getValue("deck").jsonObject.getValue("front_lang").toString())
    }

    @Test
    fun `typing and reverse need no pair`() = runBlocking {
        deckCreate(create("--type", "--reverse", "--dry-run"), FakeDeckRepository(testDeck()), null, {}) {}
        Unit
    }

    @Test
    fun `import refuses it too, before reading a deck`() = runBlocking {
        val error = assertFailsWith<CliError> {
            importDryRun(Args.parse(arrayOf("import", cardFile("a\tb"), "--dry-run", "--speak")), ImportRepositoryImpl(), onNote = {})
        }

        assertEquals(ExitCode.Usage, error.exitCode)
    }

    @Test
    fun `deck edit refuses turning a mode on without the pair`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "d1"))

        assertFailsWith<CliError> { deckEdit(Args.parse(arrayOf("deck", "edit", "d1", "--speak")), decks, {}) }
        assertEquals(emptyList(), decks.metadataWrites)
    }

    @Test
    fun `deck edit refuses clearing a language the modes depend on`() = runBlocking {
        val deck = testDeck(id = "d1").copy(listenEnabled = true, frontLang = "en-US", backLang = "es-ES")
        val decks = FakeDeckRepository(deck)

        assertFailsWith<CliError> { deckEdit(Args.parse(arrayOf("deck", "edit", "d1", "--back-lang=")), decks, {}) }
    }

    /** Published before the pair existed: the opt-in is on, the languages are not, and it must stay editable. */
    @Test
    fun `a deck that already lacks its pair can still be edited`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "d1").copy(listenEnabled = true))

        deckEdit(Args.parse(arrayOf("deck", "edit", "d1", "--title", "Renamed")), decks, {})

        assertEquals("Renamed", decks.metadataWrites.single().title)
    }

    // ---- language advice ------------------------------------------------------------------

    @Test
    fun `a language tag no picker offers is noted, never refused`() = runBlocking {
        val notes = mutableListOf<String>()

        val result = deckCreate(
            create("--front-lang", "en-US", "--back-lang", "es", "--dry-run"),
            FakeDeckRepository(testDeck()),
            null,
            notes::add,
        ) {}

        val advice = result.data.jsonObject.getValue("language_advice").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(1, advice.size)
        assertTrue(advice.single().contains("es-ES or es-MX"), advice.single())
        assertTrue(notes.any { it.contains("--back-lang es") }, notes.toString())
    }

    @Test
    fun `a listed pair draws no note`() = runBlocking {
        val result = deckCreate(
            create("--front-lang", "pt-br", "--back-lang", "en-US", "--dry-run"),
            FakeDeckRepository(testDeck()),
            null,
            {},
        ) {}

        assertEquals(0, result.data.jsonObject.getValue("language_advice").jsonArray.size)
    }

    // ---- harness --------------------------------------------------------------------------

    private fun create(vararg argv: String) = Args.parse(arrayOf("deck", "create", "--title", "T") + argv)

    private suspend fun dryRun(file: String, vararg flags: String, onNote: (String) -> Unit = {}) =
        deckCreate(create("--from-file", file, "--dry-run", *flags), FakeDeckRepository(testDeck()), null, onNote) {}

    private fun cardFile(vararg rows: String): String =
        File.createTempFile("loopky-advice", ".tsv").apply {
            deleteOnExit()
            writeText(rows.joinToString("\n") + "\n")
        }.absolutePath

    private fun card(id: String, front: String, back: String) =
        Card(id = id, deckId = "d1", updatedAt = 0L, front = CardSide(text = front), back = CardSide(text = back))

    private fun CommandResult.cardAdvice(): List<Pair<String, List<String>>> =
        data.jsonObject.getValue("card_advice").jsonArray.map {
            val advice = it.jsonObject
            advice.getValue("rule").jsonPrimitive.content to
                advice.getValue("where").jsonArray.map { w -> w.jsonPrimitive.content }
        }

    private fun JsonObject.ints(vararg keys: String): List<Int> = keys.map { getValue(it).jsonPrimitive.content.toInt() }

    private fun session() = Session(
        identity = PubkyIdentity("pk:test", null, null, null),
        sessionSecret = "secret",
        capabilities = listOf(Capability("/pub/loopky/:rw")),
        homeserver = "hs:test",
    )
}
