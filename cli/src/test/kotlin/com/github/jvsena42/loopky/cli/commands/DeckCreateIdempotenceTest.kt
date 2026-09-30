package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `deck create` with a client-supplied id (#240, finding 5).
 *
 * The failure it answers is reasoned rather than observed, and it follows from two things this
 * tool already documents: the session dies hourly, and every homeserver command costs a round
 * trip — so an agent *will* have a create killed mid-flight. With no id to name and no
 * idempotency key, its only recovery was `deck list` plus a match on title, which is neither
 * cheap nor race-free, and a plain re-run publishes a second deck.
 */
class DeckCreateIdempotenceTest {

    private val session = Session(
        identity = PubkyIdentity(pubky = "pk:test", displayName = null, avatarUrl = null, bio = null),
        sessionSecret = "pk:test:cookie",
        homeserver = "hs",
        capabilities = listOf(Capability("/pub/loopky/:rw")),
    )

    private fun create(vararg argv: String) = Args.parse(arrayOf("deck", "create") + argv)

    private val missing = IllegalStateException("Request failed: 404 Not Found - pubky://x/manifest.json")

    @Test
    fun `an id that is free is published under exactly that id`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "mine00000001"), readFails = missing)

        val result = deckCreate(create("--title", "T", "--id", "mine00000001"), decks, session, {}, {})

        assertEquals("mine00000001", result.data.jsonObject["deck"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(true, result.data.jsonObject["created"]!!.jsonPrimitive.content.toBoolean())
    }

    /** The whole point: the retry is a no-op that hands back the deck the first run made. */
    @Test
    fun `--if-not-exists returns the deck that is already there and writes nothing`() = runBlocking {
        val existing = testDeck(id = "mine00000001", cardCount = 40).copy(title = "Capitais")
        val decks = FakeDeckRepository(existing)

        val result = deckCreate(
            create("--title", "Capitais", "--id", "mine00000001", "--if-not-exists"),
            decks,
            session,
            {},
            {},
        )

        assertEquals(emptyList(), decks.published)
        assertEquals(false, result.data.jsonObject["created"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("40", result.data.jsonObject["deck"]!!.jsonObject["card_count"]!!.jsonPrimitive.content)
    }

    /**
     * `publish` replaces the manifest and its whole chunk table, so a reused id takes the deck's
     * cards with it. Nothing here prompts, so refusing is the only place that can say no.
     */
    @Test
    fun `an existing id without --if-not-exists is refused rather than published over`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "mine00000001", cardCount = 40))

        val error = assertFailsWith<CliError> {
            deckCreate(create("--title", "T", "--id", "mine00000001"), decks, session, {}, {})
        }

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue("--if-not-exists" in error.message.orEmpty(), error.message.orEmpty())
        assertEquals(emptyList(), decks.published)
    }

    /**
     * The direction that would publish the duplicate this flag exists to prevent: an unreachable
     * homeserver is not "the deck does not exist".
     */
    @Test
    fun `a read that failed for any other reason is not read as absent`() = runBlocking {
        val decks = FakeDeckRepository(
            testDeck(id = "mine00000001"),
            readFails = IllegalStateException("Request failed: HTTP transport error: connection reset"),
        )

        val error = assertFailsWith<CliError> {
            deckCreate(create("--title", "T", "--id", "mine00000001", "--if-not-exists"), decks, session, {}, {})
        }

        assertEquals(ExitCode.Network, error.exitCode)
        assertEquals(emptyList(), decks.published)
    }

    /** The ordinary path is unchanged, and costs no extra round trip. */
    @Test
    fun `without --id nothing is read first`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(), readFails = missing)

        deckCreate(create("--title", "T"), decks, session, {}, {})

        assertEquals(emptyList(), decks.fetchRemoteCalls)
    }

    /**
     * The existence check reads **one record**, from the caller's own namespace.
     *
     * `sync` is `fetchRemote` plus `fetchByDeck` — on a cold process, every chunk of the deck. That
     * turned the cheap early return into ~200 reads for a 20k-card deck, on exactly the call an
     * agent repeats. And aiming the read at `session.identity.pubky` is what makes a followed
     * deck's id unable to occupy yours: `sync` would have resolved the author through the follow
     * records first.
     */
    @Test
    fun `the existence check reads one manifest, from the caller's own namespace`() = runBlocking {
        val decks = FakeDeckRepository(testDeck(id = "mine00000001"))

        deckCreate(create("--title", "T", "--id", "mine00000001", "--if-not-exists"), decks, session, {}, {})

        assertEquals(listOf("pk:test" to "mine00000001"), decks.fetchRemoteCalls)
        assertEquals(emptyList(), decks.syncCalls, "sync pulls every chunk; the manifest is enough")
    }

    @Test
    fun `--if-not-exists without --id is a usage error, and says why`() = runBlocking {
        val decks = FakeDeckRepository(testDeck())

        val error = assertFailsWith<CliError> {
            deckCreate(create("--title", "T", "--if-not-exists"), decks, session, {}, {})
        }

        assertEquals(ExitCode.Usage, error.exitCode)
        assertTrue("--id" in error.message.orEmpty(), error.message.orEmpty())
    }

    /** A supplied id goes into a homeserver path, so it gets the same check every other one does. */
    @Test
    fun `an unusable --id is bad input`() = runBlocking {
        val decks = FakeDeckRepository(testDeck())

        for (bad in listOf("", "  ", "a/b", "..")) {
            val error = assertFailsWith<CliError>("'$bad' should be refused") {
                deckCreate(create("--title", "T", "--id", bad), decks, session, {}, {})
            }
            assertEquals(ExitCode.BadInput, error.exitCode, "for --id '$bad'")
        }
    }

    /**
     * The case the flag exists for, and the one it used to get wrong: `publish` writes an
     * `incomplete` marker manifest *before* the chunks, so a killed run leaves one behind.
     * Accepting it would report success for a deck whose cards were never uploaded.
     */
    @Test
    fun `--if-not-exists re-publishes a deck whose first publish did not finish`() = runBlocking {
        val half = testDeck(id = "mine00000001").copy(incomplete = true)
        val decks = FakeDeckRepository(half)

        val result = deckCreate(
            create("--title", "Capitais", "--id", "mine00000001", "--if-not-exists"),
            decks,
            session,
            {},
            {},
        )

        assertEquals(true, result.data.jsonObject["created"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("mine00000001", result.data.jsonObject["deck"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an incomplete deck without the flag is refused and says why`() {
        val half = testDeck(id = "mine00000001").copy(incomplete = true)
        val decks = FakeDeckRepository(half)

        val error = assertFailsWith<CliError> {
            runBlocking { deckCreate(create("--title", "T", "--id", "mine00000001"), decks, session, {}, {}) }
        }

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertTrue(error.message!!.contains("incomplete"))
    }

    /**
     * `sync` falls back to the followed-deck subscriptions, so an id colliding with a deck you
     * *follow* would otherwise be reported as taken — an id that is free in your own namespace,
     * refused with no way past, or accepted by `--if-not-exists` as someone else's deck.
     */
    @Test
    fun `a deck belonging to another author does not occupy the id`() = runBlocking {
        val theirs = testDeck(id = "mine00000001", cardCount = 40).copy(authorPubky = "pk:someone-else")
        val decks = FakeDeckRepository(theirs)

        val result = deckCreate(create("--title", "T", "--id", "mine00000001"), decks, session, {}, {})

        assertEquals(true, result.data.jsonObject["created"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("mine00000001", result.data.jsonObject["deck"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }
}
