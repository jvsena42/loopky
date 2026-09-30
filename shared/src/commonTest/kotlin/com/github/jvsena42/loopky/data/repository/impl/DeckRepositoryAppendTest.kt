package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `appendCards` exists because the cost of the alternative *was* the bug.
 *
 * `import --resume` looped `upsertCard`, and each of those is a chunk write **plus** a full
 * manifest read-modify-write — 60 homeserver writes for 30 cards where `publish` spends 2. The
 * manifest carries the whole chunk table, so the per-card cost climbs with deck size. Measured on
 * staging: 30 cards took 40.7s to resume against 4.3s to publish the same 30 fresh.
 *
 * That is not a performance footnote. `--resume` exists so an import killed by the hourly session
 * expiry (#165) can finish, and a recovery an order of magnitude slower than the attempt that ran
 * out of time means a large import never finishes — each retry dies earlier in the file. The
 * mechanism made the failure it exists for more likely.
 *
 * So these assert the **number of writes**, not only the result.
 */
class DeckRepositoryAppendTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val repo = deckRepository(pubky, session, cardRepo, revalidator, RecordingTagRepository())

    private val manifest = "pubky://$TEST_PUBKY/pub/loopky/decks/deck1/manifest.json"

    private fun manifestWrites() = pubky.puts.count { it.first == manifest }

    private suspend fun seeded(cards: Int) =
        repo.publish(testDeck(id = "deck1"), List(cards) { testCard("seed$it") }).getOrThrow()

    @Test
    fun aBatchCostsOneManifestWriteNotOnePerCard() = runTest {
        seeded(cards = 5)

        val before = manifestWrites()
        repo.appendCards("deck1", List(10) { testCard("new$it") }).getOrThrow()

        assertEquals(1, manifestWrites() - before, "ten appended cards should cost one manifest write")
    }

    @Test
    fun theCardsLandInTheDeckInTheOrderGiven() = runTest {
        val deck = seeded(cards = 3)

        val updated = repo.appendCards("deck1", listOf(testCard("a"), testCard("b"))).getOrThrow()

        assertEquals(deck.cardCount + 2, updated.cardCount)
        val stored = cardRepo.fetchByDeck(updated).getOrThrow()
        assertEquals(listOf("seed0", "seed1", "seed2", "a", "b"), stored.map { it.id })
    }

    /** Ords continue from the deck's maximum; restarting per chunk would interleave the batch. */
    @Test
    fun appendedOrdsComeAfterEverythingAlreadyThere() = runTest {
        seeded(cards = 3)

        val updated = repo.appendCards("deck1", listOf(testCard("a"))).getOrThrow()

        val stored = cardRepo.fetchByDeck(updated).getOrThrow()
        val appended = stored.single { it.id == "a" }
        assertTrue(
            appended.ord > stored.filter { it.id != "a" }.maxOf { it.ord },
            "appended ord ${appended.ord} is not past the deck's existing maximum",
        )
    }

    /** A batch bigger than one record spills into new chunks — still one manifest patch. */
    @Test
    fun aBatchSpanningChunksStillCostsOneManifestWrite() = runTest {
        seeded(cards = 2)

        val before = manifestWrites()
        val updated = repo.appendCards("deck1", List(CHUNK_SIZE * 2) { testCard("new$it") }).getOrThrow()

        assertEquals(1, manifestWrites() - before)
        assertEquals(CHUNK_SIZE * 2 + 2, updated.cardCount)
        // The chunk table has to describe every record the cards went into, or they leave the deck
        // with nothing reporting an error.
        assertEquals(updated.cardCount, updated.chunks.sumOf { it.count })
    }

    /**
     * The boundary case, and the one a fabricated ord floor got wrong.
     *
     * When the last chunk is **full**, the append target is a chunk that does not exist yet — so
     * the tail read comes back empty and there is nothing local to continue from. Guessing put the
     * appended cards *before* ones already in the deck, silently scrambling study order for
     * exactly the decks big enough to have filled a chunk.
     */
    @Test
    fun appendingOntoAFullChunkStillOrdersAfterTheDeck() = runTest {
        seeded(cards = CHUNK_SIZE)

        val updated = repo.appendCards("deck1", listOf(testCard("a"))).getOrThrow()

        val stored = cardRepo.fetchByDeck(updated).getOrThrow()
        assertEquals("a", stored.last().id, "the appended card should sort last, order was ${stored.map { it.id }}")
        assertTrue(stored.single { it.id == "a" }.ord > stored.filter { it.id != "a" }.maxOf { it.ord })
    }

    /**
     * `updateMetadata` writes the `updatedAt` it is handed — it re-reads `chunks` and `cardCount`
     * inside the lock but not the timestamp.
     *
     * That is the contract, and it is a trap for the append-then-describe sequence `import
     * --resume` runs: the metadata snapshot was taken *before* the append, so passing its
     * timestamp rewinds the manifest below what the append set, and `hasUpdate` compares exactly
     * that field against a follower's last-seen mark. A follower who had already seen the deck
     * would never be told the new cards exist.
     */
    @Test
    fun updateMetadataWritesTheTimestampItIsGiven() = runTest {
        val before = seeded(cards = 2)
        val appended = repo.appendCards("deck1", listOf(testCard("a"))).getOrThrow()
        assertTrue(appended.updatedAt >= before.updatedAt)

        // What a caller must *not* do: describe the deck with the snapshot it captured earlier.
        val rewound = repo.updateMetadata(before.copy(title = "renamed")).getOrThrow()
        assertEquals(before.updatedAt, rewound.updatedAt)

        // And what it must do instead — carry the post-append timestamp across.
        val carried = repo.updateMetadata(
            before.copy(title = "renamed again", updatedAt = appended.updatedAt),
        ).getOrThrow()
        assertEquals(appended.updatedAt, carried.updatedAt)
        // Either way the chunk table survives, because that half *is* re-read inside the lock.
        assertEquals(appended.cardCount, carried.cardCount)
    }

    @Test
    fun anEmptyBatchIsANoOpRatherThanAManifestWrite() = runTest {
        seeded(cards = 2)

        val before = pubky.puts.size
        val updated = repo.appendCards("deck1", emptyList()).getOrThrow()

        assertEquals(before, pubky.puts.size)
        assertEquals(2, updated.cardCount)
    }

    /** Replacing a card is `upsertCard`, which knows how to find the chunk it lives in. */
    @Test
    fun anIdAlreadyInTheDeckIsRefusedRatherThanDuplicated() = runTest {
        seeded(cards = 2)

        val failure = repo.appendCards("deck1", listOf(testCard("seed0"))).exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "was $failure")
    }

    @Test
    fun aCardWithAnEmptySideIsRefusedLikeEveryOtherWritePath() = runTest {
        seeded(cards = 2)

        val blank = testCard("blank").copy(back = CardSide())
        assertTrue(repo.appendCards("deck1", listOf(blank)).isFailure)
    }
}
