package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `card mv`. The write itself is `DeckRepository.moveCard`, covered in `:shared`; what is tested
 * here is the index the command hands it, which is read against the deck *without* the moved card.
 */
class CardMoveTest {

    private fun card(id: String, ord: Long) = Card(
        id = id,
        deckId = "d1",
        updatedAt = 0L,
        front = CardSide(text = id),
        back = CardSide(text = "back of $id"),
        ord = ord,
    )

    // Deliberately out of ord order: positions are study order, not the order records came back in.
    private val cards = FakeCardRepository(
        existing = listOf(card("c", 3000), card("a", 1000), card("d", 4000), card("b", 2000)),
    )

    private fun decks() = FakeDeckRepository(testDeck(cardCount = 4))

    private fun move(vararg args: String) = Args.parse(arrayOf("card", "mv", "d1") + args)

    @Test
    fun `--to counts from one over the study order`() = runBlocking {
        val decks = decks()

        val result = cardMove(move("d", "--to", "1"), decks, cards)

        assertEquals(listOf("d" to 0), decks.moves)
        assertEquals(1, result.data.jsonObject.getValue("position").jsonPrimitive.int)
        assertTrue(result.data.jsonObject.getValue("moved").jsonPrimitive.boolean)
    }

    @Test
    fun `moving a card forwards lands it at the position asked for`() = runBlocking {
        val decks = decks()

        cardMove(move("a", "--to", "3"), decks, cards)

        // Without "a" the deck is b, c, d: index 2 puts it between c and d, third of four.
        assertEquals(listOf("a" to 2), decks.moves)
    }

    @Test
    fun `--after lands the card behind its anchor in both directions`() = runBlocking {
        val decks = decks()

        cardMove(move("a", "--after", "c"), decks, cards)
        cardMove(move("d", "--after", "a"), decks, cards)

        assertEquals(listOf("a" to 2, "d" to 1), decks.moves)
    }

    @Test
    fun `a position past the end is the end`() = runBlocking {
        val decks = decks()

        val result = cardMove(move("a", "--to", "99"), decks, cards)

        assertEquals(listOf("a" to 3), decks.moves)
        assertEquals(4, result.data.jsonObject.getValue("position").jsonPrimitive.int)
    }

    /** What makes a batch of moves safe to re-run after a session expiry. */
    @Test
    fun `a card already in place writes nothing`() = runBlocking {
        val decks = decks()

        val byPosition = cardMove(move("b", "--to", "2"), decks, cards)
        val byAnchor = cardMove(move("b", "--after", "a"), decks, cards)

        assertTrue(decks.moves.isEmpty())
        assertEquals(false, byPosition.data.jsonObject.getValue("moved").jsonPrimitive.boolean)
        assertEquals(false, byAnchor.data.jsonObject.getValue("moved").jsonPrimitive.boolean)
    }

    @Test
    fun `a card or an anchor the deck does not have is not_found`() {
        val missingCard = assertFailsWith<CliError> {
            runBlocking { cardMove(move("zz", "--to", "1"), decks(), cards) }
        }
        val missingAnchor = assertFailsWith<CliError> {
            runBlocking { cardMove(move("a", "--after", "zz"), decks(), cards) }
        }

        assertEquals(ExitCode.NotFound, missingCard.exitCode)
        assertEquals(ExitCode.NotFound, missingAnchor.exitCode)
    }

    @Test
    fun `neither target, both, or a card after itself is refused before any read`() {
        val neither = assertFailsWith<CliError> { runBlocking { cardMove(move("a"), decks(), cards) } }
        val both = assertFailsWith<CliError> {
            runBlocking { cardMove(move("a", "--to", "1", "--after", "b"), decks(), cards) }
        }
        // A card the deck lacks, so the answer shows which check ran first.
        val itself = assertFailsWith<CliError> {
            runBlocking { cardMove(move("zz", "--after", "zz"), decks(), cards) }
        }

        assertEquals(ExitCode.Usage, neither.exitCode)
        assertEquals(ExitCode.Usage, both.exitCode)
        assertEquals(ExitCode.BadInput, itself.exitCode)
        assertEquals(0, cards.deckReads)
    }

    /** `--json` is how a move is verified, so it carries the stored `ord`, never the one it had. */
    @Test
    fun `the card comes back with the ord the move stored`() = runBlocking {
        cards.stored = { id -> card(id, ord = 500) }

        val result = cardMove(move("d", "--to", "1"), decks(), cards)

        val ord = result.data.jsonObject.getValue("card").jsonObject.getValue("ord").jsonPrimitive.content
        assertEquals("500", ord)
    }

    @Test
    fun `a moved card that cannot be read back is an error, not a stale card`() {
        cards.stored = { null }

        val error = assertFailsWith<CliError> { runBlocking { cardMove(move("d", "--to", "1"), decks(), cards) } }

        assertEquals(ExitCode.Internal, error.exitCode)
    }
}
