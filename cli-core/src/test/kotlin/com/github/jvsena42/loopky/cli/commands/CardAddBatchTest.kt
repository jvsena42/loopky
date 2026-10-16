package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `card add --from-file` writes in groups, not card by card.
 *
 * One `upsertCard` per card is a chunk write plus a whole-manifest read-modify-write each: 170
 * cards took ten minutes and said nothing until the end, where `deck create` writes 1210 in
 * seconds (#257, item 2). Nothing about that was visible from a green test — the cards did land.
 */
class CardAddBatchTest {

    private fun fileOf(rows: Int): String {
        val file = File.createTempFile("cards", ".tsv").also { it.deleteOnExit() }
        file.writeText((1..rows).joinToString("\n") { "front $it\tback $it" })
        return file.absolutePath
    }

    private fun addArgs(path: String, vararg extra: String) =
        Args.parse(arrayOf("card", "add", "d1", "--from-file", path, *extra))

    @Test
    fun `a file is appended in groups rather than one card at a time`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 0))

        cardAdd(addArgs(fileOf(250)), decks, FakeCardRepository())

        assertEquals(listOf(100, 100, 50), decks.appendBatches)
        assertEquals(emptyList(), decks.upsertAttempts, "an add never goes through upsertCard")
    }

    /** The counter `deck create` already emits, so a large batch is not a silent ten minutes. */
    @Test
    fun `progress is reported as the groups land`() = runBlocking {
        val progress = mutableListOf<String>()

        cardAdd(
            addArgs(fileOf(250)),
            FakeDeckRepository(testDeck(cardCount = 0)),
            FakeCardRepository(),
            onProgress = progress::add,
        )

        assertEquals(3, progress.size)
        assertContains(progress.first(), "100/250 cards")
        assertContains(progress.last(), "250/250 cards")
    }

    /**
     * A group either lands whole or not at all, so its rows are all reported failed and the
     * groups after it are not attempted. The result still travels on the failure envelope: it is
     * the only thing that says which rows are on the homeserver.
     */
    @Test
    fun `a failed group reports its rows and stops the batch`() = runBlocking {
        var call = 0
        val decks = FakeDeckRepository(
            testDeck(cardCount = 0),
            onAppend = { cards ->
                call++
                if (call == 2) {
                    Result.failure(IllegalStateException("507 Insufficient Storage"))
                } else {
                    Result.success(testDeck(cardCount = cards.size))
                }
            },
        )

        val error = assertFailsWith<CliError> { cardAdd(addArgs(fileOf(250)), decks, FakeCardRepository()) }

        val data = requireNotNull(error.data).jsonObject
        assertEquals("100", data.getValue("written").jsonPrimitive.content)
        assertEquals("100", data.getValue("failed").jsonPrimitive.content)
        assertEquals("50", data.getValue("not_attempted").jsonPrimitive.content)
        assertEquals(ExitCode.StorageFull, error.exitCode)
        assertContains(error.message.orEmpty(), "Re-run the same file")
    }
}
