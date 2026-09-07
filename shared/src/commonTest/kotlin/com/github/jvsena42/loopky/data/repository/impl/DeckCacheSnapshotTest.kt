package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.repository.CachedDecks
import com.github.jvsena42.loopky.data.storage.decodeDeckCache
import com.github.jvsena42.loopky.data.storage.encodeDeckCache
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeDeckCacheStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device-local snapshot behind [com.github.jvsena42.loopky.data.repository.DeckRepository.listCached],
 * which is what lets Home, the library and Profile paint before a single request has answered.
 */
class DeckCacheSnapshotTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val cache = FakeDeckCacheStore()
    private val repo = deckRepository(
        pubky = pubky,
        session = session,
        cardRepo = cardRepo,
        revalidator = revalidator,
        deckCache = cache,
    )

    @Test
    fun nothingIsCachedUntilAListingHasSucceeded() = runTest {
        assertNull(repo.listCached())
    }

    @Test
    fun listingTheLibraryLeavesASnapshotThatNeedsNoRequests() = runTest {
        repo.publish(testDeck(id = "deck1", title = "Spanish"), listOf(testCard("c1"))).getOrThrow()
        repo.listOwned()

        // The point of the snapshot: a second reader gets the library with the network taken away.
        pubky.store.clear()
        val cached = repo.listCached()

        assertEquals(listOf("Spanish"), cached?.owned?.map { it.title })
        assertEquals(1, cached?.owned?.single()?.cardCount)
        assertTrue(cached?.followed.orEmpty().isEmpty())
    }

    /**
     * A snapshot carrying a chunk table could be patched back onto a manifest and orphan the card
     * records it no longer describes (§8.0). Dropping it is what makes the copy unusable for a
     * write, which is the only guarantee stopping one.
     */
    @Test
    fun theSnapshotCarriesNoChunkTable() = runTest {
        repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()
        repo.listOwned()

        assertTrue(repo.listCached()?.owned?.single()?.chunks.orEmpty().isEmpty())
    }

    /** The two halves are listed by calls that fail independently — one must not erase the other. */
    @Test
    fun aFailedFollowedListingDoesNotEraseTheOwnedDecks() = runTest {
        val followed = testDeck(id = "theirs", authorPubky = "friendpk", title = "Theirs")
        cache.save(TEST_PUBKY, CachedDecks(owned = emptyList(), followed = listOf(followed)))

        repo.publish(testDeck(id = "deck1", title = "Mine"), listOf(testCard("c1"))).getOrThrow()
        repo.listOwned()

        val cached = repo.listCached()
        assertEquals(listOf("Mine"), cached?.owned?.map { it.title })
        assertEquals(listOf("Theirs"), cached?.followed?.map { it.title })
    }

    /** A snapshot is a claim about a person; a new account must not inherit the last one's library. */
    @Test
    fun aSnapshotWrittenByAnotherAccountReadsAsAbsent() {
        val payload = encodeDeckCache(
            "someoneelse",
            CachedDecks(owned = listOf(testDeck(id = "deck1")), followed = emptyList()),
        )

        assertNull(decodeDeckCache(payload, TEST_PUBKY))
    }

    @Test
    fun anUnreadableSnapshotIsAbsentRatherThanAFailure() {
        assertNull(decodeDeckCache("{not json", TEST_PUBKY))
        assertNull(decodeDeckCache("", TEST_PUBKY))
        assertNull(decodeDeckCache(null, TEST_PUBKY))
    }
}
