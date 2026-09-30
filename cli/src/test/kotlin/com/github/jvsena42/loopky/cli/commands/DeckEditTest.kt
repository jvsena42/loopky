package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.remoteImageRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `deck edit` — the overlay rules, asserted against what it *wrote* rather than what it printed.
 *
 * The command exists because the alternative was destructive (#222): changing a deck's cover meant
 * `deck delete` plus `deck create`, which mints a new deck id and throws every card's review state
 * away. What makes it safe is the overlay — a flag you did not pass must leave that field exactly
 * as it was — so that is what most of these check, one field at a time against a deck that has a
 * value in every one of them.
 */
class DeckEditTest {

    private val deck = testDeck(cardCount = 12).copy(
        title = "Capitais",
        description = "Capitais do mundo",
        coverEmoji = "🌍",
        tags = listOf(Tag("geografia"), Tag("capitais")),
        listenEnabled = true,
        speakEnabled = true,
        typeEnabled = false,
        reverseEnabled = false,
        frontLang = "pt-BR",
        backLang = "en-US",
    )

    private fun edit(vararg argv: String) = Args.parse(arrayOf("deck", "edit", "d1") + argv)

    @Test
    fun `a field nobody named is left exactly as it was`() = runBlocking {
        val decks = FakeDeckRepository(deck)

        deckEdit(edit("--title", "Capitais do mundo"), decks)

        val written = decks.metadataWrites.single()
        assertEquals("Capitais do mundo", written.title)
        assertEquals(deck.description, written.description)
        assertEquals(deck.coverEmoji, written.coverEmoji)
        assertEquals(deck.tags, written.tags)
        assertEquals(deck.frontLang, written.frontLang)
        assertEquals(deck.backLang, written.backLang)
        assertEquals(deck.listenEnabled, written.listenEnabled)
        assertEquals(deck.speakEnabled, written.speakEnabled)
        assertEquals(deck.typeEnabled, written.typeEnabled)
        assertEquals(deck.reverseEnabled, written.reverseEnabled)
    }

    /** `card edit --back=`'s gesture, on the deck's own optional fields. */
    @Test
    fun `an explicitly empty value clears the field it names`() = runBlocking {
        val decks = FakeDeckRepository(deck)

        deckEdit(edit("--description="), decks)

        assertNull(decks.metadataWrites.single().description)
    }

    @Test
    fun `--tag replaces the tag set rather than appending to it`() = runBlocking {
        val decks = FakeDeckRepository(deck)

        deckEdit(edit("--tag", "europa", "--tag", "quiz"), decks)

        assertEquals(listOf(Tag("europa"), Tag("quiz")), decks.metadataWrites.single().tags)
    }

    @Test
    fun `--clear-tags empties it`() = runBlocking {
        val decks = FakeDeckRepository(deck)

        deckEdit(edit("--clear-tags"), decks)

        assertEquals(emptyList(), decks.metadataWrites.single().tags)
    }

    /**
     * The half of retagging that is easy to miss: retyping a deck away from English has to take
     * `"english"` off it, or the deck stays listed as English forever (see `LanguageTags.retag`).
     */
    @Test
    fun `changing the pair swaps the labels the old pair contributed`() = runBlocking {
        val decks = FakeDeckRepository(
            deck.copy(tags = listOf(Tag("geografia"), Tag("language"), Tag("portuguese"), Tag("english"))),
        )

        deckEdit(edit("--back-lang", "es-ES"), decks)

        val written = decks.metadataWrites.single()
        assertEquals("es-ES", written.backLang)
        assertEquals(
            listOf(Tag("geografia"), Tag("language"), Tag("portuguese"), Tag("spanish")),
            written.tags,
        )
    }

    /**
     * `--clear-tags` has to be believed. The language labels are ordinary author-removable tags,
     * so re-deriving them on an edit that never touched the pair would make the one gesture that
     * empties the set unable to empty it.
     */
    @Test
    fun `--clear-tags is not undone by the deck's declared languages`() = runBlocking {
        val decks = FakeDeckRepository(deck.copy(tags = listOf(Tag("language"), Tag("portuguese"))))

        deckEdit(edit("--clear-tags"), decks)

        assertEquals(emptyList(), decks.metadataWrites.single().tags)
    }

    @Test
    fun `--no-listen turns an opt-in off, and --type turns one on`() = runBlocking {
        val decks = FakeDeckRepository(deck)

        deckEdit(edit("--no-listen", "--type"), decks)

        val written = decks.metadataWrites.single()
        assertEquals(false, written.listenEnabled)
        assertEquals(true, written.typeEnabled)
        assertEquals(true, written.speakEnabled)
    }

    @Test
    fun `--cover-url stores a remote ref, and --clear-cover removes both halves of the cover`() = runBlocking {
        val url = "https://example.org/capitais.jpg"
        val withCover = FakeDeckRepository(deck)
        deckEdit(edit("--cover-url", url), withCover)
        assertEquals(url, withCover.metadataWrites.single().coverImageRef?.url)

        val cleared = FakeDeckRepository(deck.copy(coverImageRef = remoteImageRef(url)))
        deckEdit(edit("--clear-cover"), cleared)
        val written = cleared.metadataWrites.single()
        assertNull(written.coverImageRef)
        assertNull(written.coverEmoji)
    }

    /**
     * A manifest write bumps `updated_at`, which is what every follower's "the author published
     * changes" badge reads — so re-running an edit that already landed must not write again.
     */
    @Test
    fun `an edit that changes nothing writes nothing and says so`() = runBlocking {
        val decks = FakeDeckRepository(deck)

        val json = deckEdit(edit("--title", "Capitais", "--tag", "geografia", "--tag", "capitais"), decks)
            .data.jsonObject

        assertEquals(emptyList(), decks.metadataWrites)
        assertEquals("false", json.getValue("changed").jsonPrimitive.content)
        assertEquals(0, json.getValue("fields").jsonArray.size)
    }

    @Test
    fun `the envelope names the fields that actually moved`() = runBlocking {
        val decks = FakeDeckRepository(deck)

        val json = deckEdit(edit("--title", "Capitais 2", "--no-speak"), decks).data.jsonObject

        assertEquals("true", json.getValue("changed").jsonPrimitive.content)
        assertEquals(
            listOf("title", "speak_enabled"),
            json.getValue("fields").jsonArray.map { it.jsonPrimitive.content },
        )
        // And the deck comes back with the homeserver's card count, not a recomputed one.
        assertEquals("12", json.getValue("deck").jsonObject.getValue("card_count").jsonPrimitive.content)
    }

    @Test
    fun `naming no field at all is a usage error, not an empty write`() = runBlocking {
        val decks = FakeDeckRepository(deck)

        val error = assertFailsWith<CliError> { deckEdit(edit(), decks) }

        assertEquals(ExitCode.Usage, error.exitCode)
        assertEquals(emptyList(), decks.metadataWrites)
    }

    @Test
    fun `--clear-tags and --tag together are refused rather than guessed at`() = runBlocking {
        val error = assertFailsWith<CliError> {
            deckEdit(edit("--clear-tags", "--tag", "europa"), FakeDeckRepository(deck))
        }
        assertEquals(ExitCode.Usage, error.exitCode)
    }

    @Test
    fun `an empty --title is refused, since a deck cannot have none`() = runBlocking {
        val error = assertFailsWith<CliError> { deckEdit(edit("--title="), FakeDeckRepository(deck)) }
        assertEquals(ExitCode.Usage, error.exitCode)
    }

    /** The same refusal `deck create` and `card add` give: an http:// picture renders on neither app. */
    @Test
    fun `an http cover URL is refused before it reaches the manifest`() = runBlocking {
        val decks = FakeDeckRepository(deck)

        val error = assertFailsWith<CliError> {
            deckEdit(edit("--cover-url", "http://example.org/x.jpg"), decks)
        }

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue(decks.metadataWrites.isEmpty())
    }
}
