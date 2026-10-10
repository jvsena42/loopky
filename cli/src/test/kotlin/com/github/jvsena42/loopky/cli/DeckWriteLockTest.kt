package com.github.jvsena42.loopky.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckWriteLockTest {

    private val home = Files.createTempDirectory("loopky-locks").also { it.toFile().deleteOnExit() }
    private val lock = DeckWriteLock(home, pollMillis = 20L)

    @Test
    fun `a second write to the same deck waits for the first and says so`() = runBlocking {
        val order = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val firstHolds = CompletableDeferred<Unit>()

        val first = async {
            lock.holding("d1", {}) {
                firstHolds.complete(Unit)
                delay(600)
                order += "first"
            }
        }
        firstHolds.await()
        lock.holding("d1", notes::add) { order += "second" }
        first.await()

        assertEquals(listOf("first", "second"), order)
        assertTrue(notes.first().contains("d1"), notes.toString())
    }

    /** One line then silence is a hang from the outside; the caller needs to see it is still waiting. */
    @Test
    fun `a long wait is announced again, with how long it has been`() = runBlocking {
        val notes = mutableListOf<String>()
        val firstHolds = CompletableDeferred<Unit>()
        // 120 polls of 5 ms between notes: held for a second, the second note has to arrive.
        val quick = DeckWriteLock(home, pollMillis = 5L)

        val first = async {
            quick.holding("d1", {}) {
                firstHolds.complete(Unit)
                delay(1_000)
            }
        }
        firstHolds.await()
        quick.holding("d1", notes::add) { }
        first.await()

        assertTrue(notes.size >= 2, notes.toString())
        assertTrue(notes[1].contains("so far"), notes[1])
    }

    @Test
    fun `an id that could be a path is never turned into a file`() = runBlocking {
        assertEquals("ran", lock.holding("../outside", {}) { "ran" })
        assertTrue(Files.notExists(home.resolve("outside.lock")))
    }

    @Test
    fun `writes to different decks do not wait for each other`() = runBlocking {
        val notes = mutableListOf<String>()

        lock.holding("d1", notes::add) {
            lock.holding("d2", notes::add) { }
        }

        assertEquals(emptyList(), notes)
    }

    @Test
    fun `the lock is released when the command fails`() = runBlocking {
        runCatching { lock.holding<Unit>("d1", {}) { error("write failed") } }
        val notes = mutableListOf<String>()

        lock.holding("d1", notes::add) { }

        assertEquals(emptyList(), notes)
    }

    /** A config home that cannot hold a lock file must not fail the write over bookkeeping. */
    @Test
    fun `a home that cannot hold a lock runs the command unlocked`() = runBlocking {
        val notAFolder = Files.createTempFile("loopky-not-a-dir", "").also { it.toFile().deleteOnExit() }

        assertEquals("ran", DeckWriteLock(notAFolder).holding("d1", {}) { "ran" })
    }

    @Test
    fun `only a command that writes one known deck is locked`() {
        fun target(vararg argv: String) = DeckWriteLock.target(Args.parse(arrayOf(*argv)))

        assertEquals("d1", target("card", "add", "d1", "--front", "a", "--back", "b"))
        assertEquals("d1", target("deck", "edit", "d1", "--title", "T"))
        assertEquals("mine", target("deck", "create", "--title", "T", "--id", "mine"))
        assertNull(target("card", "list", "d1"))
        assertNull(target("card", "add", "d1", "--from-file", "f", "--dry-run"))
        assertNull(target("deck", "create", "--title", "T"))
        // Never a path: an id that is not a plain word is left for the command to refuse.
        assertNull(target("card", "rm", "../x", "c1"))
    }
}
