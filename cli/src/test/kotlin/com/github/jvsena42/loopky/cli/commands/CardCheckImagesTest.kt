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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `card check-images`: the write-time check, over what a deck already holds (#454). */
class CardCheckImagesTest {

    private fun card(id: String, frontImage: String? = null, backImage: String? = null) = Card(
        id = id,
        deckId = "d1",
        updatedAt = 0L,
        front = CardSide(text = "f$id", imageRef = frontImage?.let(::remoteImage)),
        back = CardSide(text = "b$id", imageRef = backImage?.let(::remoteImage)),
    )

    private val cards = listOf(
        card("c1", frontImage = "https://x.test/ok.jpg"),
        card("c2", frontImage = "https://x.test/gone.jpg"),
        card("c3", backImage = "https://x.test/gone.jpg"),
        card("c4", backImage = "https://x.test/slow.jpg"),
        card("c5"),
    )

    private val args = Args.parse(arrayOf("card", "check-images", "d1"))

    private fun fakeCheck(
        asked: MutableList<String> = mutableListOf(),
    ): suspend (Collection<String>) -> List<ImageCheck> = { urls ->
        asked += urls
        urls.mapNotNull {
            when {
                it.endsWith("gone.jpg") -> ImageCheck(it, status = 404, reason = "the host refused it")
                it.endsWith("slow.jpg") -> ImageCheck(it, status = 429, unverified = true, reason = "rate-limited")
                else -> null
            }
        }
    }

    @Test
    fun `every stored picture url is asked about once and nothing is written`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 5))
        val asked = mutableListOf<String>()

        val json = cardCheckImages(args, decks, FakeCardRepository(cards), {}, fakeCheck(asked)).data.jsonObject

        assertEquals(listOf("https://x.test/ok.jpg", "https://x.test/gone.jpg", "https://x.test/slow.jpg"), asked)
        val counts = listOf("checked", "ok", "wrong", "unverified").map { json.getValue(it).jsonPrimitive.content }
        assertEquals(listOf("3", "1", "1", "1"), counts)
        assertTrue(decks.upserted.isEmpty())
    }

    /** The reason this is its own shape: a 404 is only fixable once you know which cards carry it. */
    @Test
    fun `a finding names every card the url is on`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 5))

        val result = cardCheckImages(args, decks, FakeCardRepository(cards), {}, fakeCheck())

        val gone = result.data.jsonObject.getValue("image_checks").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("url").jsonPrimitive.content.endsWith("gone.jpg") }
        assertEquals(listOf("c2", "c3"), gone.getValue("card_ids").jsonArray.map { it.jsonPrimitive.content })
        assertContains(result.text, "c2,c3")
        assertContains(result.text.lines().last(), "1 ok, 1 wrong, 1 could not be checked")
    }

    @Test
    fun `a deck with no picture urls asks nobody`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(cardCount = 1))

        val result = cardCheckImages(args, decks, FakeCardRepository(listOf(card("c5"))), {}) {
            error("nothing should be probed")
        }

        assertContains(result.text, "no picture URLs")
    }

    @Test
    fun `the concurrency dial needs no --check-images here, and is still bounded`() {
        Args.parse(arrayOf("card", "check-images", "d1", "--check-images-concurrency", "1")).requireImageCheckOptions()

        val error = assertFailsWith<CliError> {
            Args.parse(arrayOf("card", "check-images", "d1", "--check-images-concurrency", "99")).requireImageCheckOptions()
        }
        assertEquals(ExitCode.Usage, error.exitCode)
    }
}
