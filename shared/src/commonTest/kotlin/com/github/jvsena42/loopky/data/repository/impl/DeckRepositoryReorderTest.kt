package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.ManifestDto
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
 * `DeckRepository.reorderCards` — a whole deck put into a given order (#449).
 *
 * The property that matters is the one a chunked layout makes hard: the cards move between
 * records over several requests, and at no point between them may a card be on none.
 */
class DeckRepositoryReorderTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val repo = deckRepository(pubky, session, cardRepo, revalidator, RecordingTagRepository())

    private val deckRoot = "pubky://$TEST_PUBKY/pub/loopky/decks/deck1"
    private val ids = (1..250).map { "c$it" }

    private suspend fun publish() {
        repo.publish(testDeck(id = "deck1"), ids.map { testCard(it) }).getOrThrow()
        pubky.puts.clear()
    }

    @Test
    fun aReorderIsWhatAFreshReaderSees() = runTest {
        publish()
        val wanted = ids.reversed()

        val deck = repo.reorderCards("deck1", wanted).getOrThrow()

        assertEquals(wanted, freshRead())
        assertEquals(expected = 250, actual = deck.cardCount)
        assertEquals(listOf(100, 100, 50), deck.chunks.map { it.count })
        assertEquals(listOf(100, 100, 50), storedChunks().map { it.size }, "a record kept cards it gave away")
    }

    @Test
    fun cardIdsSurviveSoReviewStateDoes() = runTest {
        publish()

        repo.reorderCards("deck1", ids.shuffled(kotlin.random.Random(7))).getOrThrow()

        assertEquals(ids.toSet(), freshRead().toSet())
    }

    @Test
    fun aDeckAlreadyInThatOrderWritesNothing() = runTest {
        publish()

        repo.reorderCards("deck1", ids).getOrThrow()

        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun onlyTheChunksWhoseOrderChangedAreWritten() = runTest {
        publish()
        // Swap two cards inside the last chunk; chunks 0 and 1 keep their order.
        val wanted = ids.toMutableList().apply { this[248] = "c250"; this[249] = "c249" }

        repo.reorderCards("deck1", wanted).getOrThrow()

        // The marker, the chunk, the table, the stamps. No trim: nothing left the chunk.
        assertEquals(
            listOf(
                "$deckRoot/manifest.json",
                "$deckRoot/cards/2.json",
                "$deckRoot/manifest.json",
                "$deckRoot/manifest.json",
            ),
            pubky.puts.map { it.first },
        )
        assertEquals(false, manifest().reorder_pending)
    }

    @Test
    fun aChunkGrownByMovesIsBroughtBackToSize() = runTest {
        publish()
        repeat(20) { repo.moveCard("deck1", "c${250 - it}", toIndex = 0).getOrThrow() }
        assertEquals(listOf(120, 100, 30), repo.getLocal("deck1")!!.chunks.map { it.count })
        val order = freshRead()

        val deck = repo.reorderCards("deck1", order.reversed()).getOrThrow()

        assertEquals(listOf(100, 100, 50), deck.chunks.map { it.count })
        assertEquals(order.reversed(), freshRead())
    }

    @Test
    fun aSparseTableIsRepackedAndItsSpareRecordDeleted() = runTest {
        publish()
        // 60 cards deleted from the middle chunk: 190 cards fit in two records, the table has three.
        (101..160).forEach { repo.deleteCard("deck1", "c$it").getOrThrow() }
        val order = freshRead()
        pubky.deletes.clear()

        val deck = repo.reorderCards("deck1", order.reversed()).getOrThrow()

        assertEquals(listOf(0 to 100, 1 to 90), deck.chunks.map { it.n to it.count })
        assertEquals(listOf("$deckRoot/cards/2.json"), pubky.deletes)
        assertEquals(order.reversed(), freshRead())
    }

    @Test
    fun aListThatIsNotThisDecksCardsFailsBeforeAnyWrite() = runTest {
        publish()

        val missing = repo.reorderCards("deck1", ids.dropLast(1))
        val unknown = repo.reorderCards("deck1", ids.dropLast(1) + "nope")
        val duplicate = repo.reorderCards("deck1", ids.dropLast(1) + "c1")

        assertTrue(missing.isFailure && unknown.isFailure && duplicate.isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    /**
     * The session dies after every possible number of writes. Whatever was reached, a reader finds
     * all 250 cards, and running the same reorder again finishes it.
     */
    @Test
    fun aReorderInterruptedAnywhereLosesNoCardAndFinishesOnRerun() = runTest {
        val wanted = ids.shuffled(kotlin.random.Random(42))
        publish()
        repo.reorderCards("deck1", wanted).getOrThrow()
        val writes = pubky.puts.size + pubky.deletes.size

        for (allowed in 0 until writes) {
            val run = Fixture()
            run.publish(ids)
            // Someone with the deck open before any of it: their cache is warm and stays theirs.
            val studying = run.reader()
            assertEquals(ids, studying.read())
            run.pubky.sessionCallsBeforeFailure = allowed

            assertTrue(run.repo.reorderCards("deck1", wanted).isFailure, "write $allowed did not fail")
            assertEquals(ids.toSet(), run.freshRead().toSet(), "a card was lost after $allowed writes")
            assertEquals(ids.toSet(), studying.read().toSet(), "the open deck lost a card after $allowed writes")
            // Opens the deck while it is half rewritten, and keeps the app open.
            val arrivedMidway = run.reader()
            assertEquals(ids.toSet(), arrivedMidway.read().toSet())

            run.pubky.sessionCallsBeforeFailure = null
            run.rerun(wanted)
            assertEquals(wanted, run.freshRead(), "the re-run after $allowed writes left the wrong order")
            assertEquals(listOf(100, 100, 50), run.storedChunkSizes(), "leftovers after $allowed writes")
            assertEquals(wanted, studying.read(), "the open deck kept a stale order after $allowed writes")
            assertEquals(wanted, arrivedMidway.read(), "a reader who arrived midway is stale after $allowed writes")

            // The author later deletes a card: nobody may go on seeing it.
            run.rerunDelete(wanted.first())
            assertEquals(wanted.drop(1), studying.read(), "a deleted card lingers after $allowed writes")
            assertEquals(wanted.drop(1), arrivedMidway.read(), "a deleted card lingers midway after $allowed writes")
        }
    }

    /** Between the two passes a card sits in two records; both copies have to sort the same way. */
    @Test
    fun aReaderArrivingBetweenThePassesSeesTheNewOrder() = runTest {
        val wanted = ids.reversed()
        val run = Fixture()
        run.publish(ids)
        // The marker, three union writes and the table land; the trims do not.
        run.pubky.sessionCallsBeforeFailure = 5

        run.repo.reorderCards("deck1", wanted)

        assertEquals(wanted, run.freshRead())
    }

    private suspend fun freshRead(): List<String> = Fixture.read(pubky)

    private fun storedChunks(): List<List<String>> =
        manifest().chunks.map { meta ->
            loopkyJson.decodeFromString<CardChunkDto>(pubky.store.getValue("$deckRoot/cards/${meta.n}.json"))
                .cards.map { it.id }
        }

    private fun manifest(): ManifestDto =
        loopkyJson.decodeFromString(pubky.store.getValue("$deckRoot/manifest.json"))

    /** A homeserver of its own, so each interruption starts from an untouched deck. */
    private class Fixture {
        val pubky = FakePubkyClient()
        private val session = signedInProvider()
        private val revalidator = CountingRevalidator()
        private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
        val repo = deckRepository(pubky, session, cardRepo, revalidator, RecordingTagRepository())

        suspend fun publish(ids: List<String>) {
            repo.publish(testDeck(id = "deck1"), ids.map { testCard(it) }).getOrThrow()
        }

        /** As a new process would: nothing cached, the manifest read from the homeserver. */
        suspend fun rerun(wanted: List<String>) {
            val cards = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
            deckRepository(pubky, session, cards, revalidator, RecordingTagRepository())
                .reorderCards("deck1", wanted).getOrThrow()
        }

        suspend fun freshRead(): List<String> = read(pubky)

        suspend fun rerunDelete(cardId: String) {
            val cards = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
            deckRepository(pubky, session, cards, revalidator, RecordingTagRepository())
                .deleteCard("deck1", cardId).getOrThrow()
        }

        /** A reader that keeps its card cache between reads, the way an open app does. */
        fun reader(): WarmReader = WarmReader(pubky)

        fun storedChunkSizes(): List<Int> {
            val root = "pubky://$TEST_PUBKY/pub/loopky/decks/deck1"
            val manifest = loopkyJson.decodeFromString<ManifestDto>(pubky.store.getValue("$root/manifest.json"))
            return manifest.chunks.map { meta ->
                loopkyJson.decodeFromString<CardChunkDto>(pubky.store.getValue("$root/cards/${meta.n}.json"))
                    .cards.size
            }
        }

        companion object {
            /** The deck as a reader with an empty cache gets it, in study order. */
            suspend fun read(pubky: FakePubkyClient): List<String> {
                val session = signedInProvider()
                val revalidator = CountingRevalidator()
                val cards = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
                val decks = deckRepository(pubky, session, cards, revalidator, RecordingTagRepository())
                val deck = decks.fetchRemote(TEST_PUBKY, "deck1").getOrThrow()
                return cards.fetchByDeck(deck).getOrThrow().map { it.id }
            }
        }
    }

    private class WarmReader(private val pubky: FakePubkyClient) {
        private val session = signedInProvider()
        private val revalidator = CountingRevalidator()
        private val cards = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)

        /** Re-reads the manifest every time and the cards through the cache, as a re-sync does. */
        suspend fun read(): List<String> {
            val decks = deckRepository(pubky, session, cards, revalidator, RecordingTagRepository())
            val deck = decks.fetchRemote(TEST_PUBKY, "deck1").getOrThrow()
            return cards.fetchByDeck(deck).getOrThrow().map { it.id }
        }
    }

    private companion object {
        init {
            check(CHUNK_SIZE == 100) { "these counts assume 100-card records" }
        }
    }
}
