package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.FakeMediaRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.data.repository.impl.ImportRepositoryImpl
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `--expect-hash`: a dry run's `content_hash` handed to the real call, which refuses any other
 * content without the server keeping anything between the two (#559).
 */
class ContentHashTest {

    private val session = Session(
        identity = PubkyIdentity(pubky = "pk:test", displayName = null, avatarUrl = null, bio = null),
        sessionSecret = "pk:test:cookie",
        homeserver = "hs",
        capabilities = listOf(Capability("/pub/loopky/:rw")),
    )

    private fun fileOf(text: String, extension: String = ".tsv"): String =
        File.createTempFile("cards", extension).apply {
            deleteOnExit()
            writeText(text)
        }.absolutePath

    private fun JsonObject.hash(): String = getValue("content_hash").jsonPrimitive.content

    private suspend fun create(vararg argv: String, decks: FakeDeckRepository = FakeDeckRepository(testDeck())) =
        deckCreate(Args.parse(arrayOf("deck", "create", *argv)), decks, session, {}, {}).data.jsonObject

    @Test
    fun `deck create's dry run and real call agree, though each mints its own deck and card ids`() = runBlocking {
        val cards = fileOf("hola\thello\nadiós\tbye")
        val dry = create("--title", "Saludos", "--from-file", cards, "--tag", "Español", "--dry-run")
        val decks = FakeDeckRepository(testDeck())

        val real =
            create("--title", "Saludos", "--from-file", cards, "--tag", "espanol", "--expect-hash", dry.hash(), decks = decks)

        // `Español` and `espanol` are stored as the same tag, so they are the same content.
        assertEquals(dry.hash(), real.hash())
        assertEquals(listOf("hola", "adiós"), decks.published.map { it.front.text })
    }

    @Test
    fun `deck create refuses content the dry run did not check, as a usage error, writing nothing`() = runBlocking {
        val dry = create("--title", "Saludos", "--from-file", fileOf("hola\thello"), "--dry-run")
        val decks = FakeDeckRepository(testDeck())

        val changedCard = assertFailsWith<CliError> {
            create("--title", "Saludos", "--from-file", fileOf("hola\thi"), "--expect-hash", dry.hash(), decks = decks)
        }
        val changedTitle = assertFailsWith<CliError> {
            create("--title", "Hola", "--from-file", fileOf("hola\thello"), "--expect-hash", dry.hash(), decks = decks)
        }
        val changedMode = assertFailsWith<CliError> {
            create("--title", "Saludos", "--from-file", fileOf("hola\thello"), "--type", "--expect-hash", dry.hash(), decks = decks)
        }

        listOf(changedCard, changedTitle, changedMode).forEach { assertEquals(ExitCode.Usage, it.exitCode) }
        assertTrue(changedCard.message.orEmpty().contains("Nothing was written"))
        assertEquals(emptyList(), decks.published)
    }

    @Test
    fun `card add hashes the rows it carries, so a retry after a partial write still matches`() = runBlocking {
        val tsv = fileOf("hola\thello\nadiós\tbye")
        val add = { extra: String, value: String? ->
            Args.parse(arrayOf("card", "add", "d1", "--from-file", tsv, extra) + listOfNotNull(value))
        }
        val dry = cardAdd(add("--dry-run", null), FakeDeckRepository(testDeck()), FakeCardRepository(), {}, {}).data.jsonObject

        val alreadyThere =
            Card(id = "c1", deckId = "d1", updatedAt = 0, front = CardSide("hola"), back = CardSide("hello"))
        val decks = FakeDeckRepository(testDeck())
        val retried = cardAdd(add("--expect-hash", dry.hash()), decks, FakeCardRepository(listOf(alreadyThere)), {}, {})
            .data.jsonObject

        assertEquals(dry.hash(), retried.hash())
        assertEquals(listOf("adiós"), decks.appended.map { it.front.text })

        val other = Args.parse(arrayOf("card", "add", "d2", "--from-file", tsv, "--expect-hash", dry.hash()))
        val refused = assertFailsWith<CliError> { cardAdd(other, decks, FakeCardRepository(), {}, {}) }
        assertEquals(ExitCode.Usage, refused.exitCode)
    }

    @Test
    fun `import's dry run and real run agree, and a different text is refused`() = runBlocking {
        val text = fileOf("hola\thello\nadiós\tbye\n", ".txt")
        val dry = importDryRun(Args.parse(arrayOf("import", text, "--title", "Saludos", "--dry-run")), ImportRepositoryImpl(), {})
            .data.jsonObject
        val decks = FakeDeckRepository(testDeck())

        val real = runImport(decks, text, "--title", "Saludos", "--expect-hash", dry.hash())
        assertEquals(dry.hash(), real.hash())
        assertEquals(2, decks.published.size)

        val refused = assertFailsWith<CliError> {
            val other = fileOf("hola\thello\nadiós\tgoodbye\n", ".txt")
            runImport(FakeDeckRepository(testDeck()), other, "--title", "Saludos", "--expect-hash", dry.hash())
        }
        assertEquals(ExitCode.Usage, refused.exitCode)
        val untitled = importDryRun(Args.parse(arrayOf("import", text, "--dry-run")), ImportRepositoryImpl(), {}).data.jsonObject
        assertNotEquals(dry.hash(), untitled.hash(), "the title is part of what is written")
    }

    private suspend fun runImport(decks: FakeDeckRepository, path: String, vararg argv: String): JsonObject =
        import(
            args = Args.parse(arrayOf("import", path, *argv)),
            imports = ImportRepositoryImpl(),
            decks = decks,
            cards = FakeCardRepository(),
            media = FakeMediaRepository(),
            session = session,
            onProgress = {},
            onNote = {},
        ).data.jsonObject
}
