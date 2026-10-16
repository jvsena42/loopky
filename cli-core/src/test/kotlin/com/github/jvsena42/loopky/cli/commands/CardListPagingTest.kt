package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.MediaRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Paging and filtering `card list`.
 *
 * Deciding which of 4,000 cards still wanted a picture meant dumping the whole deck — ~700 KB —
 * on every round of a retry loop (#229, item 6). There is no server-side filter to ask for
 * instead: the homeserver stores opaque records and Nexus indexes tags, not cards. What there *is*
 * is the manifest's chunk table, so a page can fetch only the records it needs — which is the
 * property these tests are for, not the shape of the output.
 */
class CardListPagingTest {

    private fun card(id: String, ord: Long, image: String? = null) = Card(
        id = id,
        deckId = "d1",
        updatedAt = 0L,
        front = CardSide(
            text = id,
            imageRef = image?.let {
                MediaRef.Image(path = "", mime = "image/jpeg", sha256 = "", width = null, height = null, url = it)
            },
        ),
        back = CardSide(text = "back of $id"),
        ord = ord,
    )

    private val chunkTable = listOf(
        ChunkMeta(n = 0, count = 2, updatedAt = 0L),
        // Not contiguous: compaction folds a pair and drops the higher n, so 1 is simply gone.
        ChunkMeta(n = 2, count = 2, updatedAt = 0L),
    )

    private val chunks = mapOf(
        0 to listOf(card("a", 1000), card("b", 2000, image = "https://x.test/b.jpg")),
        2 to listOf(card("c", 3000), card("d", 4000, image = "https://x.test/d.jpg")),
    )

    private fun list(vararg extra: String) =
        Args.parse(arrayOf("card", "list", "d1") + extra)

    private fun ids(json: JsonElement) =
        json.jsonObject.getValue("cards").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    private fun decks() = FakeDeckRepository(testDeck(cardCount = 4, chunks = chunkTable))

    @Test
    fun `a limit reads only the chunks the page needs`() = runBlocking {
        val cards = FakeCardRepository(chunks = chunks)

        val result = cardList(list("--limit", "2"), decks(), cards) {}

        assertEquals(listOf("a", "b"), ids(result.data))
        assertEquals(listOf(0, 2), cards.chunksRead, "one chunk past the page, to learn there is more")
        assertEquals("2:0", result.data.jsonObject.getValue("next_cursor").jsonPrimitive.content)
    }

    /** The card that did not fit is served next time, not skipped. */
    @Test
    fun `a cursor resumes exactly where the page stopped`() = runBlocking {
        val cards = FakeCardRepository(chunks = chunks)

        val result = cardList(list("--limit", "2", "--cursor", "2:0"), decks(), cards) {}

        assertEquals(listOf("c", "d"), ids(result.data))
        assertEquals(listOf(2), cards.chunksRead, "the first chunk is never fetched again")
    }

    @Test
    fun `the last page reports no cursor`() = runBlocking {
        val result = cardList(list("--limit", "10"), decks(), FakeCardRepository(chunks = chunks)) {}

        assertEquals(listOf("a", "b", "c", "d"), ids(result.data))
        assertEquals(JsonNull, result.data.jsonObject.getValue("next_cursor"))
    }

    @Test
    fun `missing-image is the question a picture pass actually asks`() = runBlocking {
        val result = cardList(list("--limit", "10", "--missing-image"), decks(), FakeCardRepository(chunks = chunks)) {}

        assertEquals(listOf("a", "c"), ids(result.data))
        // `count` is this answer; `card_count` is still the deck.
        assertEquals("2", result.data.jsonObject.getValue("count").jsonPrimitive.content)
        assertEquals("4", result.data.jsonObject.getValue("card_count").jsonPrimitive.content)
    }

    @Test
    fun `has-image is its inverse`() = runBlocking {
        val result = cardList(list("--limit", "10", "--has-image"), decks(), FakeCardRepository(chunks = chunks)) {}

        assertEquals(listOf("b", "d"), ids(result.data))
    }

    /**
     * Plain `card list` means the whole deck, and it must not start walking the table for it — a
     * fake that refuses `readChunk` is the assertion.
     */
    @Test
    fun `an unpaged listing still reads the deck in one call`() = runBlocking {
        val cards = FakeCardRepository(listOf(card("a", 1000), card("b", 2000)))

        val result = cardList(list(), decks(), cards) {}

        assertEquals(listOf("a", "b"), ids(result.data))
        assertEquals(emptyList(), cards.chunksRead)
    }

    @Test
    fun `opposite filters are a usage error rather than a precedence rule`() = runBlocking {
        val error = assertFailsWith<CliError> {
            cardList(list("--missing-image", "--has-image"), decks(), FakeCardRepository(chunks = chunks)) {}
        }
        assertEquals(ExitCode.Usage, error.exitCode)
    }

    @Test
    fun `a cursor this command did not produce is refused`() = runBlocking {
        val error = assertFailsWith<CliError> {
            cardList(list("--cursor", "page2"), decks(), FakeCardRepository(chunks = chunks)) {}
        }
        assertEquals(ExitCode.Usage, error.exitCode)
    }

    /** Compaction drops the higher `n` of a folded pair, so a cursor can name a chunk that is gone. */
    @Test
    fun `a cursor into a chunk that has been folded away resumes at the next one`() = runBlocking {
        val result = cardList(list("--cursor", "1:0", "--limit", "10"), decks(), FakeCardRepository(chunks = chunks)) {}

        assertEquals(listOf("c", "d"), ids(result.data))
    }
}
