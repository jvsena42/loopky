package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.data.repository.DeckReorderPendingException
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `card reorder`. The write is `DeckRepository.reorderCards`, covered in `:shared`; this is the
 * file it is handed, and the refusal of one that is not the deck's cards.
 */
class CardReorderTest {

    private fun card(id: String, ord: Long) = Card(
        id = id,
        deckId = "d1",
        updatedAt = 0L,
        front = CardSide(text = id),
        back = CardSide(text = "back of $id"),
        ord = ord,
    )

    private val cards = FakeCardRepository(
        existing = listOf(card("a", 1000), card("b", 2000), card("c", 3000), card("d", 4000)),
    )

    private fun decks() = FakeDeckRepository(testDeck(cardCount = 4))

    private fun reorder(order: String, vararg extra: String): Args {
        val file = File.createTempFile("order", ".txt").apply {
            deleteOnExit()
            writeText(order)
        }
        return Args.parse(arrayOf("card", "reorder", "d1", "--from-file", file.path) + extra)
    }

    @Test
    fun `the file's ids are handed over in file order`() = runBlocking {
        val decks = decks()

        val result = cardReorder(reorder("d\nc\nb\na\n"), decks, cards)

        assertEquals(listOf(listOf("d", "c", "b", "a")), decks.reorders)
        assertEquals(4, result.data.jsonObject.getValue("moved").jsonPrimitive.int)
    }

    /** So `card list` output can be edited and handed straight back. */
    @Test
    fun `only the first column is read, and blanks and comments are skipped`() = runBlocking {
        val decks = decks()

        cardReorder(reorder("# episode 1\nb\tfront [img:x]\tback\n\n  a  \nd\tx\ty\nc\n"), decks, cards)

        assertEquals(listOf(listOf("b", "a", "d", "c")), decks.reorders)
    }

    @Test
    fun `a dry run counts what would move and hands nothing over`() = runBlocking {
        val decks = decks()

        val result = cardReorder(reorder("a\nb\nd\nc\n", "--dry-run"), decks, cards)

        assertTrue(decks.reorders.isEmpty())
        assertEquals(2, result.data.jsonObject.getValue("moved").jsonPrimitive.int)
        assertTrue(result.data.jsonObject.getValue("dry_run").jsonPrimitive.boolean)
        assertEquals(false, result.data.jsonObject.getValue("written").jsonPrimitive.boolean)
    }

    @Test
    fun `a file that is not the deck's cards is refused, saying which and how`() {
        val decks = decks()
        fun refused(order: String) =
            assertFailsWith<CliError> { runBlocking { cardReorder(reorder(order), decks, cards) } }

        val missing = refused("a\nb\nc\n")
        val unknown = refused("a\nb\nc\nd\nzz\n")
        val duplicate = refused("a\nb\nc\nd\na\n")

        assertEquals(ExitCode.BadInput, missing.exitCode)
        assertContains(missing.message.orEmpty(), "1 in the deck and missing from the file (d)")
        assertContains(unknown.message.orEmpty(), "1 not in the deck (zz)")
        assertContains(duplicate.message.orEmpty(), "1 named more than once (a)")
        assertTrue(decks.reorders.isEmpty())
    }

    /** A card write on a deck whose reorder died: exit 9 with the command that fixes it, never 1. */
    @Test
    fun `a write refused for an unfinished reorder says how to finish it`() {
        val error = asCliError(DeckReorderPendingException("d1"))

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertContains(error.message.orEmpty(), "loopky card reorder d1 --from-file")
        assertContains(error.message.orEmpty(), "loopky card list d1")
    }

    @Test
    fun `no file is a usage error`() {
        val error = assertFailsWith<CliError> {
            runBlocking { cardReorder(Args.parse(arrayOf("card", "reorder", "d1")), decks(), cards) }
        }

        assertEquals(ExitCode.Usage, error.exitCode)
    }
}
