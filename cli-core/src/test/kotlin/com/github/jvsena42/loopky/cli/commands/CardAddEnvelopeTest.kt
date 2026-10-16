package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `card add`'s `--json`, asserted field by field.
 *
 * This exists because the envelope was wrong and everything else was green. `CardWriteResult`
 * takes `(deckId, cards, written, skipped = 0, removed = 0, cardCount = 0)`, and `cardAdd`
 * constructed it **positionally** with five arguments — so when `removed` was inserted between
 * `skipped` and `cardCount`, the deck's size silently became the number of cards the call had
 * deleted and `card_count` fell back to its default 0. Both fields are `Int`, so nothing failed to
 * compile, and no test looked at the output.
 *
 * That is the failure `--json` is least able to absorb. Architecture.md §13.3: an agent cannot look
 * at a screenshot to check what it wrote, so a read has to echo back what was stored — which is
 * worth nothing if the echo is a different field.
 */
class CardAddEnvelopeTest {

    private val front = "Brasília"
    private val back = "Capital do Brasil"

    private fun addArgs() = Args.parse(arrayOf("card", "add", "d1", "--front", front, "--back", back))

    @Test
    fun `card_count is the deck's size, and removed is not`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 7))

        val json = cardAdd(addArgs(), decks, FakeCardRepository()).data.jsonObject

        // The two that were swapped. `card_count` is what the deck holds after the write; a
        // command that only adds has removed nothing, ever.
        assertEquals("8", json.getValue("card_count").jsonPrimitive.content)
        assertEquals("0", json.getValue("removed").jsonPrimitive.content)
    }

    @Test
    fun `and the rest of the envelope still says what it did`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 0))

        val json = cardAdd(addArgs(), decks, FakeCardRepository()).data.jsonObject

        assertEquals("d1", json.getValue("deck_id").jsonPrimitive.content)
        assertEquals("1", json.getValue("written").jsonPrimitive.content)
        assertEquals("0", json.getValue("skipped").jsonPrimitive.content)
    }

    /**
     * The idempotence guarantee, read through the envelope rather than through a counter:
     * re-running after a session expiry is the documented recovery, so "already there" has to be
     * distinguishable from "wrote it again".
     */
    @Test
    fun `a row already in the deck is skipped, not written`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 1))
        val existing = listOf(
            Card(
                id = "c1",
                deckId = "d1",
                updatedAt = 0L,
                front = CardSide(text = front),
                back = CardSide(text = back),
            ),
        )

        val json = cardAdd(addArgs(), decks, FakeCardRepository(existing)).data.jsonObject

        assertEquals("0", json.getValue("written").jsonPrimitive.content)
        assertEquals("1", json.getValue("skipped").jsonPrimitive.content)
        assertEquals("0", json.getValue("removed").jsonPrimitive.content)
        assertEquals("1", json.getValue("card_count").jsonPrimitive.content)
        assertEquals(emptyList(), decks.upserted)
    }
}
