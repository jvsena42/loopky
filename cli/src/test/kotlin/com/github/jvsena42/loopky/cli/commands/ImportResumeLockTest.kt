package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.DeckWriteLock
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.FakeMediaRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.data.repository.impl.ImportRepositoryImpl
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `import --resume` appends to a deck that exists, so it has to wait its turn like `card add`.
 * Its deck is found by title rather than named on the command line, which is how it was the one
 * write the lock did not cover.
 */
class ImportResumeLockTest {

    private val home = Files.createTempDirectory("loopky-resume-lock").also { it.toFile().deleteOnExit() }
    private val lock = DeckWriteLock(home, pollMillis = 20L)
    private val existing = testDeck(id = "d1").copy(title = TITLE)

    @Test
    fun `a resumed import waits for a command already writing its deck`() = runBlocking {
        val order = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val otherHolds = CompletableDeferred<Unit>()
        val decks = FakeDeckRepository(
            existing,
            owned = listOf(existing),
            onAppend = {
                order += "import"
                Result.success(existing)
            },
        )

        val other = async {
            lock.holding("d1", {}) {
                otherHolds.complete(Unit)
                delay(400)
                order += "other"
            }
        }
        otherHolds.await()
        runImport(decks, notes::add, "--resume")
        other.await()

        assertEquals(listOf("other", "import"), order)
        assertTrue(notes.any { it.contains("writing deck d1") }, notes.toString())
    }

    /** A new deck has nobody to wait for, and no id to lock until it is minted. */
    @Test
    fun `an import that publishes a new deck takes no lock`() = runBlocking {
        val decks = FakeDeckRepository(existing, onPublish = { deck, _ -> Result.success(deck) })

        lock.holding("d1", {}) { runImport(decks, {}) }

        assertTrue(Files.list(home.resolve("locks")).use { files -> files.count() } == 1L)
    }

    private suspend fun runImport(decks: FakeDeckRepository, onNote: (String) -> Unit, vararg extra: String) {
        val tsv = File.createTempFile("loopky-resume-lock", ".tsv").apply {
            deleteOnExit()
            writeText("hola\thello\nadios\tbye\n")
        }
        import(
            args = Args.parse(arrayOf("import", tsv.path, "--title", TITLE, *extra)),
            imports = ImportRepositoryImpl(),
            decks = decks,
            cards = FakeCardRepository(),
            media = FakeMediaRepository(),
            session = Session(
                identity = PubkyIdentity(pubky = "pk:test", displayName = null, avatarUrl = null, bio = null),
                sessionSecret = "secret",
                capabilities = listOf(Capability("/pub/loopky/:rw")),
                homeserver = "hs:test",
            ),
            onProgress = {},
            onNote = onNote,
            lock = lock,
        )
    }

    private companion object {
        const val TITLE = "Test import"
    }
}
