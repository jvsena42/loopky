package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `image_advice` on the commands that write cards, not only on `import`.
 *
 * The advice used to be printed straight to stderr as each row was parsed, which put it *before*
 * `--check-images`'s block — the ordering `import` was changed to stop doing — left it uncapped,
 * and kept it off `--json` entirely. That last one is the whole point: `deck create --from-file
 * --dry-run` is documented as the pre-flight for a file you are about to publish with, and its
 * envelope carried the opt-in network findings while omitting the always-on knowable ones (#261
 * review round 2, finding 1).
 */
class ImageAdviceEnvelopeTest {

    private val session = Session(
        identity = PubkyIdentity(pubky = "pk:test", displayName = null, avatarUrl = null, bio = null),
        sessionSecret = "pk:test:cookie",
        homeserver = "hs",
        capabilities = listOf(Capability("/pub/loopky/:rw")),
    )

    /** Two rows whose pictures are knowably wrong: an SVG original and a width Wikimedia refuses. */
    private fun badPictureFile(rows: Int = 2): String {
        val file = File.createTempFile("cards", ".tsv").also { it.deleteOnExit() }
        file.writeText(
            (1..rows).joinToString("\n") {
                "front $it\tback $it\thttps://upload.wikimedia.org/wikipedia/commons/0/03/F$it.svg\t"
            },
        )
        return file.absolutePath
    }

    @Test
    fun `card add carries it, and reports it once the whole file is read`() = runBlocking {
        val notes = mutableListOf<String>()

        val json = cardAdd(
            Args.parse(arrayOf("card", "add", "d1", "--from-file", badPictureFile(), "--dry-run")),
            FakeDeckRepository(testDeck(cardCount = 0)),
            FakeCardRepository(),
            onNote = notes::add,
        ).data.jsonObject

        val advice = json.getValue("image_advice").jsonArray
        assertEquals(2, advice.size)
        assertEquals(
            listOf("Line 1, column 3"),
            advice[0].jsonObject.getValue("where").jsonArray.map { it.jsonPrimitive.content },
        )
        // One block at the end, not one note per row as the file is parsed.
        assertEquals(1, notes.count { it.startsWith("loopky: 2 picture URL(s) are knowably wrong") })
    }

    @Test
    fun `deck create carries it, cover included`() = runBlocking {
        val json = deckCreate(
            Args.parse(
                arrayOf(
                    "deck", "create", "--title", "T", "--from-file", badPictureFile(1), "--dry-run",
                    "--cover-url", "https://upload.wikimedia.org/wikipedia/commons/0/03/Cover.svg",
                ),
            ),
            FakeDeckRepository(testDeck()),
            session,
            {},
            {},
        ).data.jsonObject

        val where = json.getValue("image_advice").jsonArray
            .flatMap { it.jsonObject.getValue("where").jsonArray.map { w -> w.jsonPrimitive.content } }
        assertEquals(listOf("Line 1, column 3", "--cover-url"), where)
    }

    @Test
    fun `card edit carries it too`() = runBlocking {
        val file = File.createTempFile("edits", ".jsonl").also { it.deleteOnExit() }
        file.writeText(
            """{"id":"c1","front_image_url":"https://upload.wikimedia.org/wikipedia/commons/0/03/F.svg"}""" + "\n",
        )
        val existing = FakeCardRepository(
            listOf(readCardFile(badPictureFile(1)).single().toCard("d1", 0L, 0).copy(id = "c1")),
        )

        val json = cardEdit(
            Args.parse(arrayOf("card", "edit", "d1", "--from-file", file.absolutePath)),
            FakeDeckRepository(testDeck(cardCount = 1)),
            existing,
            onNote = {},
        ).data.jsonObject

        assertEquals(1, json.getValue("image_advice").jsonArray.size)
    }

    /**
     * One row per distinct URL, carrying every place it appears — the property `--check-images`
     * already states for its own findings. The same broken picture on forty cards is one thing to
     * fix, and forty copies of the same multi-line paragraph is the redundancy this change set
     * exists to remove, reappearing in the array that replaced it (#261 review round 3).
     */
    @Test
    fun `one picture on many cards is one row, listing them all`() = runBlocking {
        val file = File.createTempFile("cards", ".tsv").also { it.deleteOnExit() }
        val svg = "https://upload.wikimedia.org/wikipedia/commons/0/03/Same.svg"
        file.writeText((1..40).joinToString("\n") { "front $it\tback $it\t$svg\t" })

        val json = cardAdd(
            Args.parse(arrayOf("card", "add", "d1", "--from-file", file.absolutePath, "--dry-run")),
            FakeDeckRepository(testDeck(cardCount = 0)),
            FakeCardRepository(),
            onNote = {},
        ).data.jsonObject

        val advice = json.getValue("image_advice").jsonArray
        assertEquals(1, advice.size)
        assertEquals(svg, advice.single().jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals(40, advice.single().jsonObject.getValue("where").jsonArray.size)
    }

    /** A file whose pictures are fine says nothing, on either channel. */
    @Test
    fun `nothing to say produces an empty array and no block`() = runBlocking {
        val file = File.createTempFile("cards", ".tsv").also { it.deleteOnExit() }
        file.writeText("a\tb\thttps://example.test/fine.jpg\t\n")
        val notes = mutableListOf<String>()

        val json = cardAdd(
            Args.parse(arrayOf("card", "add", "d1", "--from-file", file.absolutePath, "--dry-run")),
            FakeDeckRepository(testDeck(cardCount = 0)),
            FakeCardRepository(),
            onNote = notes::add,
        ).data.jsonObject

        assertEquals(0, json.getValue("image_advice").jsonArray.size)
        assertTrue(notes.none { "knowably wrong" in it })
    }
}
