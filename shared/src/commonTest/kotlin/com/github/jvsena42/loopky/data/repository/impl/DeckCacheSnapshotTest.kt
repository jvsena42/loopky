package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.CachedDecks
import com.github.jvsena42.loopky.data.storage.decodeDeckCache
import com.github.jvsena42.loopky.data.storage.encodeDeckCache
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeDeckCacheStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
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

    /**
     * The snapshot is what the *next* launch paints, so a degraded read must never be written as
     * though it were the whole library — the live screen recovers on the next load, the snapshot
     * does not.
     */
    @Test
    fun aPartiallyUnreadableListingDoesNotOverwriteAGoodSnapshot() = runTest {
        repo.publish(testDeck(id = "deck1", title = "Spanish"), listOf(testCard("c1"))).getOrThrow()
        repo.publish(testDeck(id = "deck2", title = "Biology"), listOf(testCard("c2", deckId = "deck2")))
            .getOrThrow()
        repo.listOwned()
        assertEquals(2, repo.listCached()?.owned?.size)

        // One manifest now fails transiently. The listing still returns the deck it could read —
        // one unreadable deck must not hide the rest — but that half-answer is not the library.
        pubky.failGetWhenUrlContains = "decks/deck2/manifest.json"
        assertEquals(1, repo.listOwned().size)

        assertEquals(
            listOf("Spanish", "Biology"),
            repo.listCached()?.owned?.map { it.title }?.sortedDescending(),
        )
    }

    /**
     * The subscription listing answers `[]` for "follows nothing" and for "could not read" alike,
     * so a flaky launch used to wipe the followed half — and `loadSubscriptions` then memoised the
     * empty read for the rest of the process, re-wiping it on every later call.
     */
    @Test
    fun anUnreadableSubscriptionListingDoesNotEraseTheFollowedDecks() = runTest {
        followATheirsDeck()
        assertEquals(listOf("Theirs"), repo.listCached()?.followed?.map { it.title })

        // A fresh repository over the same homeserver and the same snapshot: the next launch, on
        // a flaky network. The subscriptions are re-read here rather than served from the session
        // memo, which is what makes the failure reachable at all.
        val nextLaunch = nextLaunchRepo()
        pubky.failListWhenUrlContains = "subscriptions"
        assertEquals(emptyList(), nextLaunch.listFollowed())

        assertEquals(listOf("Theirs"), nextLaunch.listCached()?.followed?.map { it.title })
    }

    /** The compounding half of the same bug: an incomplete read must not be memoised either. */
    @Test
    fun anUnreadableSubscriptionListingIsNotMemoisedForTheSession() = runTest {
        followATheirsDeck()

        val nextLaunch = nextLaunchRepo()
        pubky.failListWhenUrlContains = "subscriptions"
        assertEquals(emptyList(), nextLaunch.listFollowed())

        // The homeserver comes back, and so must the follows — not "follows nothing" until restart.
        pubky.failListWhenUrlContains = null
        assertEquals(listOf("Theirs"), nextLaunch.listFollowed().map { it.title })
    }

    private suspend fun followATheirsDeck() {
        val theirs = testDeck(id = "orig", authorPubky = "friendpk", title = "Theirs")
        pubky.store[PubkyPaths.manifest("friendpk", "orig")] =
            loopkyJson.encodeToString(theirs.toDto())
        repo.followDeck(theirs).getOrThrow()
        repo.listFollowed()
    }

    /** A second repository over the same homeserver and snapshot — i.e. the next cold start. */
    private fun nextLaunchRepo() = deckRepository(
        pubky = pubky,
        session = session,
        cardRepo = cardRepo,
        revalidator = revalidator,
        deckCache = cache,
    )

    /**
     * A homeserver that ignores `cursor` repeats a page, and the paging loop stops rather than
     * spinning — but what it has is the first page, not the library. Reported as an ordinary
     * success it would be cached as one, which is finding 3 again through a different door.
     */
    @Test
    fun aTruncatedListingIsNotCached() = runTest {
        repo.publish(testDeck(id = "deck1", title = "Spanish"), listOf(testCard("c1"))).getOrThrow()
        repo.listOwned()
        assertEquals(listOf("Spanish"), repo.listCached()?.owned?.map { it.title })

        // A genuinely full page — a short one is the ordinary end of a listing and stays complete —
        // handed back unchanged however the cursor advances, i.e. a homeserver ignoring it.
        repeat(LIST_PAGE_SIZE.toInt()) { n ->
            val id = "bulk${n.toString().padStart(4, '0')}"
            pubky.store[PubkyPaths.manifest(TEST_PUBKY, id)] =
                loopkyJson.encodeToString(testDeck(id = id, title = "Bulk $n").toDto())
        }
        pubky.ignoresListCursor = true
        assertEquals(LIST_PAGE_SIZE.toInt(), repo.listOwned().size)

        // Still the one-deck snapshot: the screen got the page, the cache did not get a lie.
        assertEquals(listOf("Spanish"), repo.listCached()?.owned?.map { it.title })
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

    /**
     * Every deck title, description and tag of a deleted account, in plaintext preferences — and
     * keyed by pubky, so restoring that key from its phrase would paint a library of decks that no
     * longer exist on the homeserver.
     */
    @Test
    fun deletingTheAccountEmptiesTheSnapshot() = runTest {
        repo.publish(testDeck(id = "deck1", title = "Spanish"), listOf(testCard("c1"))).getOrThrow()
        repo.listOwned()
        assertEquals(1, repo.listCached()?.owned?.size)

        identityRepository(
            pubky = pubky,
            sessionProvider = session,
            deckRepository = repo,
            deckCache = cache,
        ).deleteAccount().getOrThrow()

        // Read the store, not `listCached()`: deleting clears the session, and `listCached()`
        // answers null without one — which would make every assertion here pass on its own.
        // Cleared, not emptied: an empty snapshot would still leave a record naming the pubky.
        assertTrue(cache.cleared, "the snapshot was not cleared")
        assertNull(cache.load(TEST_PUBKY))
    }

    @Test
    fun anUnreadableSnapshotIsAbsentRatherThanAFailure() {
        assertNull(decodeDeckCache("{not json", TEST_PUBKY))
        assertNull(decodeDeckCache("", TEST_PUBKY))
        assertNull(decodeDeckCache(null, TEST_PUBKY))
    }
}
