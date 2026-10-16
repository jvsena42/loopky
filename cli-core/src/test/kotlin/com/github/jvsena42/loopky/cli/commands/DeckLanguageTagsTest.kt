package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The labels a declared language pair contributes, across every path that sets one (#225).
 *
 * `deck create` and `import` used to write `--front-lang`/`--back-lang` into the manifest and stop
 * there, so a deck published headlessly was invisible to tag browse, to `tag trending` and to
 * anyone on Nexus looking for Japanese decks — while the byte-identical deck published from a
 * phone was not, because both ViewModels route a language pick through `LanguageTags.retag`.
 * Nothing reported it: the deck published, `--json` said ok, and the only symptom was a search
 * that came back empty somewhere else entirely. So these assert on what each path *wrote*.
 *
 * Two invariants run through all of them. **Most decks are not language decks**, and one that
 * declares no pair must come out with exactly the tags it was given — umbrella included. And the
 * labels are ordinary author-removable tags, so nothing here may make a set the author asked for
 * impossible to have.
 */
class DeckLanguageTagsTest {

    // ---- deck create ----------------------------------------------------------------------

    @Test
    fun `deck create labels a declared pair`() = runBlocking {
        val decks = capturingDecks()

        deckCreate(
            args("deck", "create", "--title", "Verbos", "--tag", "verbos", "--front-lang", "en-US", "--back-lang", "es-ES"),
            decks,
            testSession(),
        ) {}

        assertEquals(
            listOf(Tag("verbos"), Tag("language"), Tag("english"), Tag("spanish")),
            decks.publishedDeck().tags,
        )
    }

    /** One label, not two: the region picks a voice, and splitting by it would halve a search. */
    @Test
    fun `a pair in one language contributes one label`() = runBlocking {
        val decks = capturingDecks()

        deckCreate(
            args("deck", "create", "--title", "Sinônimos", "--front-lang", "pt-BR", "--back-lang", "pt-PT"),
            decks,
            testSession(),
        ) {}

        assertEquals(listOf(Tag("language"), Tag("portuguese")), decks.publishedDeck().tags)
    }

    /**
     * The case that has to stay untouched: a deck of capital cities is not a language deck, and
     * `"language"` on it would be a lie a network-wide index repeats.
     */
    @Test
    fun `a deck with no declared pair is tagged with nothing extra`() = runBlocking {
        val decks = capturingDecks()

        deckCreate(args("deck", "create", "--title", "Capitais", "--tag", "geografia"), decks, testSession()) {}

        assertEquals(listOf(Tag("geografia")), decks.publishedDeck().tags)
    }

    /** A hand-typed label the pair also derives is one chip, not two. */
    @Test
    fun `a tag the pair would derive anyway is not duplicated`() = runBlocking {
        val decks = capturingDecks()

        deckCreate(
            args("deck", "create", "--title", "Kanji", "--tag", "japanese", "--front-lang", "ja-JP"),
            decks,
            testSession(),
        ) {}

        assertEquals(listOf(Tag("japanese"), Tag("language")), decks.publishedDeck().tags)
    }

    // ---- import ---------------------------------------------------------------------------

    @Test
    fun `import labels a declared pair the same way`() = runBlocking {
        val decks = capturingDecks()

        runImport(decks, "--tag", "core", "--front-lang", "ja-JP", "--back-lang", "en-US")

        assertEquals(
            listOf(Tag("core"), Tag("language"), Tag("japanese"), Tag("english")),
            decks.publishedDeck().tags,
        )
    }

    @Test
    fun `an import that declares no pair is tagged with nothing extra`() = runBlocking {
        val decks = capturingDecks()

        runImport(decks, "--tag", "biologia")

        assertEquals(listOf(Tag("biologia")), decks.publishedDeck().tags)
    }

    // ---- import --resume ------------------------------------------------------------------

    /**
     * The overlay's job. `--resume` is used by re-running the original command with a flag
     * appended, so the pair arrives again — and on a deck first published before the labels
     * existed, that re-run is what gives it them.
     */
    @Test
    fun `a resumed run labels a deck whose pair was never labelled`() = runBlocking {
        val existing = testDeck(id = "d1").copy(title = TITLE, tags = listOf(Tag("kanji")), frontLang = "ja-JP")
        val decks = resumableDecks(existing)

        runImport(decks, "--resume", "--front-lang", "ja-JP", "--back-lang", "en-US")

        assertEquals(
            listOf(Tag("kanji"), Tag("language"), Tag("japanese"), Tag("english")),
            decks.metadataWrites.single().tags,
        )
    }

    /**
     * `--resume` can *move* a pair, and the drop is the half that is easy to miss: retyping a deck
     * from Spanish to French has to take `"spanish"` off it, or the deck stays listed as Spanish
     * forever.
     */
    @Test
    fun `a resumed run that moves the pair swaps the old labels out`() = runBlocking {
        val existing = testDeck(id = "d1").copy(
            title = TITLE,
            tags = listOf(Tag("verbos"), Tag("language"), Tag("english"), Tag("spanish")),
            frontLang = "en-US",
            backLang = "es-ES",
        )
        val decks = resumableDecks(existing)

        runImport(decks, "--resume", "--back-lang", "fr-FR")

        assertEquals(
            listOf(Tag("verbos"), Tag("language"), Tag("english"), Tag("french")),
            decks.metadataWrites.single().tags,
        )
    }

    /** A bare `--resume` still costs no metadata write — reconciling a pair nobody named is not a change. */
    @Test
    fun `a bare resume writes no metadata`() = runBlocking {
        val existing = testDeck(id = "d1").copy(title = TITLE, tags = listOf(Tag("kanji")), frontLang = "ja-JP")
        val decks = resumableDecks(existing)

        runImport(decks, "--resume")

        assertEquals(emptyList(), decks.metadataWrites)
        assertTrue(decks.appended.isNotEmpty(), "expected the resumed run to append its cards")
    }

    // ---- deck edit ------------------------------------------------------------------------

    /**
     * The repair, and the reason `deck edit` reconciles on a **named** pair rather than a moved
     * one. Before this, a deck the CLI had already published with a pair and no labels could only
     * be fixed by retyping it to a different *region* of the same language — a trick rather than a
     * command.
     */
    @Test
    fun `restating the pair a deck already has materialises its labels`() = runBlocking {
        val deck = testDeck(id = "d1").copy(
            tags = listOf(Tag("geografia")),
            frontLang = "pt-BR",
            backLang = "en-US",
        )
        val decks = FakeDeckRepository(deck)

        val result = deckEdit(args("deck", "edit", "d1", "--front-lang", "pt-BR"), decks)

        assertEquals(
            listOf(Tag("geografia"), Tag("language"), Tag("portuguese"), Tag("english")),
            decks.metadataWrites.single().tags,
        )
        // The pair itself did not move, so `tags` is the only field reported as changed.
        assertTrue(result.text.contains("tags"), result.text)
    }

    /** Nothing to reconcile is still nothing to write. */
    @Test
    fun `restating the pair of an already-labelled deck writes nothing`() = runBlocking {
        val deck = testDeck(id = "d1").copy(
            tags = listOf(Tag("language"), Tag("portuguese"), Tag("english")),
            frontLang = "pt-BR",
            backLang = "en-US",
        )
        val decks = FakeDeckRepository(deck)

        deckEdit(args("deck", "edit", "d1", "--front-lang", "pt-BR", "--back-lang", "en-US"), decks)

        assertEquals(emptyList(), decks.metadataWrites)
    }

    // ---- harness --------------------------------------------------------------------------

    private fun args(vararg argv: String) = Args.parse(arrayOf(*argv))

    /** A repository that remembers the deck `publish` was handed, not only its cards. */
    private class CapturingDecks {
        var deck: Deck? = null
    }

    private val captured = CapturingDecks()

    private fun capturingDecks() = FakeDeckRepository(
        testDeck(),
        onPublish = { deck, cards ->
            captured.deck = deck
            Result.success(deck.copy(cardCount = cards.size))
        },
    )

    private fun FakeDeckRepository.publishedDeck(): Deck =
        requireNotNull(captured.deck) { "publish was never called" }

    private fun resumableDecks(existing: Deck) = FakeDeckRepository(
        existing,
        owned = listOf(existing),
        onAppend = { Result.success(existing.copy(updatedAt = 1L)) },
    )

    private fun runImport(decks: FakeDeckRepository, vararg extra: String) = runBlocking {
        val tsv = File.createTempFile("loopky-lang-tags", ".tsv").apply {
            deleteOnExit()
            writeText("hola\thello\nadios\tbye\ngracias\tthanks\n")
        }
        import(
            args = Args.parse(arrayOf("import", tsv.path, "--title", TITLE, *extra)),
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

    private companion object {
        const val TITLE = "Test import"
    }
}
