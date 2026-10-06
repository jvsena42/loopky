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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What `card edit --from-file` does to a batch it cannot finish.
 *
 * The whole file comes from one session (#229, item 2): a 665-row edit 500'd after 35 writes, said
 * nothing at all about the 35, and had no `--resume` to pick them back up — so a naive retry was
 * the only move, and it rewrote every row. Three properties fix that, and each is a test here:
 * nothing is written until everything validates, a row failure is reported rather than swallowed
 * and does not end the batch, and a re-run skips what already landed.
 */
class CardEditBatchTest {

    private fun card(id: String, front: String, back: String) = Card(
        id = id,
        deckId = "d1",
        updatedAt = 0L,
        front = CardSide(text = front),
        back = CardSide(text = back),
    )

    private val deckCards = listOf(
        card("c1", "uno", "one"),
        card("c2", "due", "two"),
        card("c3", "tre", "three"),
    )

    private fun editFile(vararg lines: String): Args {
        val file = File.createTempFile("loopky-edits", ".jsonl").apply { writeText(lines.joinToString("\n") + "\n") }
        return Args.parse(arrayOf("card", "edit", "d1", "--from-file", file.absolutePath, "--json"))
    }

    private fun edit(id: String, front: String) = """{"id":"$id","front":"$front"}"""

    /**
     * The property that makes re-running the file the recovery, instead of a `--resume` flag. A row
     * asking for what the card already says is not a cheaper write; it is no write at all.
     */
    @Test
    fun `a row that asks for what the card already says is skipped`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 3))

        val json = cardEdit(
            editFile(edit("c1", "uno"), edit("c2", "DUE")),
            decks,
            FakeCardRepository(deckCards),
        ) {}.data.jsonObject

        assertEquals("1", json.getValue("written").jsonPrimitive.content)
        assertEquals("1", json.getValue("skipped").jsonPrimitive.content)
        assertEquals(listOf("c2"), decks.upserted.map { it.id })
    }

    /** A mistake visible in `argv` must not first ask the homeserver about the deck (#370). */
    @Test
    fun `an empty card id is refused before the deck is read`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 3))

        val error = assertFailsWith<CliError> {
            cardEdit(Args.parse(arrayOf("card", "edit", "d1", "", "--front", "x")), decks, FakeCardRepository(deckCards)) {}
        }

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue(decks.syncCalls.isEmpty())
    }

    @Test
    fun `a file row without an id is refused before the deck is read`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 3))

        val error = assertFailsWith<CliError> {
            cardEdit(editFile("""{"front":"uno"}"""), decks, FakeCardRepository(deckCards)) {}
        }

        assertEquals(ExitCode.Usage, error.exitCode)
        assertTrue(decks.syncCalls.isEmpty())
    }

    /**
     * Fail fast, with the homeserver untouched. The old order interleaved validation with writes,
     * so a bad row 400 left 399 applied and reported none of them.
     */
    @Test
    fun `an unknown id fails the command before anything is written`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 3))

        val error = assertFailsWith<CliError> {
            cardEdit(editFile(edit("c1", "ONE"), edit("nope", "x")), decks, FakeCardRepository(deckCards)) {}
        }

        assertEquals(ExitCode.NotFound, error.exitCode)
        assertEquals(emptyList(), decks.upsertAttempts.map { it.id })
    }

    /**
     * A row the homeserver refuses is one row. The rows after it applied fine when they were sent
     * singly, so the batch keeps going and the caller is told exactly which one did not.
     */
    @Test
    fun `a row failure is reported and the rest of the batch still runs`() = runBlocking {
        val decks = FakeDeckRepository(
            testDeck(cardCount = 3),
            upsertFails = { card, _ -> IllegalStateException("nope").takeIf { card.id == "c2" } },
        )

        val error = assertFailsWith<CliError> {
            cardEdit(
                editFile(edit("c1", "ONE"), edit("c2", "TWO"), edit("c3", "THREE")),
                decks,
                FakeCardRepository(deckCards),
            ) {}
        }

        assertEquals(listOf("c1", "c2", "c3"), decks.upsertAttempts.map { it.id })
        val data = requireNotNull(error.data).jsonObject
        assertEquals("2", data.getValue("written").jsonPrimitive.content)
        assertEquals("1", data.getValue("failed").jsonPrimitive.content)
        assertEquals("0", data.getValue("not_attempted").jsonPrimitive.content)
        val failure = data.getValue("failures").jsonArray.single().jsonObject
        assertEquals("c2", failure.getValue("card_id").jsonPrimitive.content)
        assertEquals("2", failure.getValue("row").jsonPrimitive.content)
    }

    /** And the ids that landed travel with it, which is the whole reason `data` is on a failure. */
    @Test
    fun `the failure envelope carries the cards that did land`() = runBlocking {
        val decks = FakeDeckRepository(
            testDeck(cardCount = 3),
            upsertFails = { card, _ -> IllegalStateException("nope").takeIf { card.id == "c3" } },
        )

        val error = assertFailsWith<CliError> {
            cardEdit(
                editFile(edit("c1", "ONE"), edit("c2", "TWO"), edit("c3", "THREE")),
                decks,
                FakeCardRepository(deckCards),
            ) {}
        }

        val written = requireNotNull(error.data).jsonObject.getValue("cards").jsonArray
        assertEquals(listOf("c1", "c2"), written.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }

    /**
     * A full disk refuses every remaining row identically, so 600 more round trips against it buy
     * nothing. The batch stops and says how many it never reached.
     */
    @Test
    fun `a batch-ending failure stops and reports what was not attempted`() = runBlocking {
        val decks = FakeDeckRepository(
            testDeck(cardCount = 3),
            upsertFails = { card, _ ->
                IllegalStateException("507 Insufficient Storage").takeIf { card.id == "c1" }
            },
        )

        val error = assertFailsWith<CliError> {
            cardEdit(
                editFile(edit("c1", "ONE"), edit("c2", "TWO"), edit("c3", "THREE")),
                decks,
                FakeCardRepository(deckCards),
            ) {}
        }

        assertEquals(ExitCode.StorageFull, error.exitCode)
        assertEquals(listOf("c1"), decks.upsertAttempts.map { it.id })
        assertEquals("2", requireNotNull(error.data).jsonObject.getValue("not_attempted").jsonPrimitive.content)
    }

    /**
     * The gap in the shared layer's retry: it recovers an expiry, a 429 and an unreachable session
     * round trip, and lets a 500 straight through. That is the one this saw.
     */
    @Test
    fun `a homeserver 500 is retried rather than failing the row`() = runBlocking {
        val decks = FakeDeckRepository(
            testDeck(cardCount = 3),
            upsertFails = { _, attempt ->
                IllegalStateException("Server responded with an error: 500 Internal Server Error")
                    .takeIf { attempt == 1 }
            },
        )

        val json = cardEdit(editFile(edit("c1", "ONE")), decks, FakeCardRepository(deckCards)) {}.data.jsonObject

        assertEquals("1", json.getValue("written").jsonPrimitive.content)
        assertEquals(2, decks.upsertAttempts.size, "the first attempt should have been retried")
    }

    /**
     * The exit code is the state the run is *now* in. A full disk after an unrelated 500 is a full
     * disk — reporting the first failure would send an agent retrying against a wall.
     */
    @Test
    fun `the code reported is the failure that ended the batch`() = runBlocking {
        val decks = FakeDeckRepository(
            testDeck(cardCount = 3),
            upsertFails = { card, _ ->
                when (card.id) {
                    "c1" -> IllegalStateException("boom")
                    "c2" -> IllegalStateException("507 Insufficient Storage")
                    else -> null
                }
            },
        )

        val error = assertFailsWith<CliError> {
            cardEdit(
                editFile(edit("c1", "ONE"), edit("c2", "TWO"), edit("c3", "THREE")),
                decks,
                FakeCardRepository(deckCards),
            ) {}
        }

        assertEquals(ExitCode.StorageFull, error.exitCode)
        assertEquals("2", requireNotNull(error.data).jsonObject.getValue("failed").jsonPrimitive.content)
    }

    /** And a 500 that never clears is its own exit code, not `internal` — it is not the caller's bug. */
    @Test
    fun `a 500 that never clears exits server_error`() = runBlocking {
        val decks = FakeDeckRepository(
            testDeck(cardCount = 3),
            upsertFails = { _, _ -> IllegalStateException("500 Internal Server Error") },
        )

        val error = assertFailsWith<CliError> {
            cardEdit(editFile(edit("c1", "ONE")), decks, FakeCardRepository(deckCards)) {}
        }

        assertEquals(ExitCode.ServerError, error.exitCode)
        assertTrue(
            "Re-run the same file" in error.message.orEmpty(),
            "the message has to say how to pick the batch back up",
        )
    }

    private val pictured = card("c1", "uno", "one").copy(
        back = CardSide(text = "one", imageRef = remoteImage("https://x.test/dead.jpg")),
    )

    private fun clearing(vararg argv: String): Card {
        val decks = FakeDeckRepository(testDeck(cardCount = 1))
        val args = Args.parse(arrayOf("card", "edit", "d1", *argv))
        runBlocking { cardEdit(args, decks, FakeCardRepository(listOf(pictured))) {} }
        return decks.upserted.single()
    }

    /** #453: decoded as it stands, an explicit null is the same Kotlin `null` an absent key is. */
    @Test
    fun `a flat null image clears the picture and leaves the text alone`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 1))

        cardEdit(editFile("""{"id":"c1","back_image_url":null}"""), decks, FakeCardRepository(listOf(pictured))) {}

        assertEquals(CardSide(text = "one"), decks.upserted.single().back)
        assertEquals(pictured.front, decks.upserted.single().front)
    }

    /** The row the idempotency check used to call already applied, because only its text matched. */
    @Test
    fun `a null image beside unchanged text is a write, not a skip`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 1))

        val json = cardEdit(
            editFile("""{"id":"c1","front":"uno","back":"one","back_image_url":null}"""),
            decks,
            FakeCardRepository(listOf(pictured)),
        ) {}.data.jsonObject

        assertEquals("1", json.getValue("written").jsonPrimitive.content)
        assertEquals(null, decks.upserted.single().back.imageRef)
    }

    @Test
    fun `a row naming only an id is still refused`() {
        val error = assertFailsWith<CliError> {
            runBlocking {
                cardEdit(editFile("""{"id":"c1"}"""), FakeDeckRepository(testDeck()), FakeCardRepository(listOf(pictured))) {}
            }
        }

        assertEquals(ExitCode.BadInput, error.exitCode)
    }

    @Test
    fun `one card's picture is cleared by the switch or by an empty value`() {
        assertEquals(null, clearing("c1", "--clear-back-image").back.imageRef)
        assertEquals(null, clearing("c1", "--back-image", "").back.imageRef)
        assertEquals(null, clearing("c1", "--back-image=").back.imageRef)
    }

    @Test
    fun `clearing a picture and setting it are refused together`() {
        val error = assertFailsWith<CliError> {
            clearing("c1", "--clear-back-image", "--back-image", "https://x.test/new.jpg")
        }

        assertEquals(ExitCode.Usage, error.exitCode)
    }

    /** A switch on a file edit would otherwise be accepted and ignored. */
    @Test
    fun `the switch is refused beside a card file`() {
        val file = File.createTempFile("loopky-edits", ".jsonl").apply { writeText(edit("c1", "x") + "\n") }

        val error = assertFailsWith<CliError> { clearing("--from-file", file.absolutePath, "--clear-back-image") }

        assertEquals(ExitCode.Usage, error.exitCode)
    }

    @Test
    fun `clearing the only thing on a side is bad input, not a write`() {
        val imageOnly = pictured.copy(back = CardSide(imageRef = pictured.back.imageRef))
        val decks = FakeDeckRepository(testDeck(cardCount = 1))

        val args = Args.parse(arrayOf("card", "edit", "d1", "c1", "--clear-back-image"))

        val error = assertFailsWith<CliError> {
            runBlocking { cardEdit(args, decks, FakeCardRepository(listOf(imageOnly))) {} }
        }

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue(decks.upserted.isEmpty())
    }
}
