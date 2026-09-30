package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.testing.FakePubkyClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The paging loop's three exits, and which of them may be believed.
 *
 * "Complete" is not decoration: it is what decides whether a listing may be written to the device
 * snapshot the next launch paints from. Two of these exits used to report a truncated listing as
 * an ordinary success.
 */
class PubkyPagingTest {

    private val pubky = FakePubkyClient()
    private val prefix = "pubky://someone/pub/loopky/decks/"

    private fun seed(count: Int) {
        repeat(count) { pubky.store["$prefix${it.toString().padStart(6, '0')}/manifest.json"] = "{}" }
    }

    @Test
    fun aListingThatEndsOnAShortPageIsComplete() = runTest {
        seed(LIST_PAGE_SIZE.toInt() + 3)

        val listing = pubky.listAllEntriesPartial(prefix)

        assertEquals(LIST_PAGE_SIZE.toInt() + 3, listing.entries.size)
        assertNull(listing.failure)
        assertFalse(listing.truncated)
        assertTrue(listing.isComplete)
    }

    @Test
    fun anEmptyListingIsComplete() = runTest {
        val listing = pubky.listAllEntriesPartial(prefix)

        assertTrue(listing.entries.isEmpty())
        assertTrue(listing.isComplete)
    }

    /**
     * A homeserver ignoring `cursor` hands back page one forever. Stopping is right — the old loop
     * did — but calling the first page the whole listing is what let it be cached as one.
     */
    @Test
    fun aHomeserverRepeatingAPageIsTruncatedNotComplete() = runTest {
        seed(LIST_PAGE_SIZE.toInt() * 3)
        pubky.ignoresListCursor = true

        val listing = pubky.listAllEntriesPartial(prefix)

        // What it read is still handed back — a first page beats an error for the screen.
        assertEquals(LIST_PAGE_SIZE.toInt(), listing.entries.size)
        assertNull(listing.failure, "every read succeeded; there is nothing to retry")
        assertTrue(listing.truncated)
        assertFalse(listing.isComplete)
    }

    /** The other give-up: the page ceiling, reached with a full page still to come. */
    @Test
    fun hittingThePageCeilingIsTruncatedNotComplete() = runTest {
        seed(LIST_PAGE_SIZE.toInt() * (MAX_LIST_PAGES_FOR_TEST + 1))

        val listing = pubky.listAllEntriesPartial(prefix)

        assertEquals(LIST_PAGE_SIZE.toInt() * MAX_LIST_PAGES_FOR_TEST, listing.entries.size)
        assertNull(listing.failure)
        assertTrue(listing.truncated)
        assertFalse(listing.isComplete)
    }

    @Test
    fun aFailedPageIsAFailureNotMerelyTruncated() = runTest {
        seed(LIST_PAGE_SIZE.toInt() * 2)
        pubky.failListAfterPages = 1

        val listing = pubky.listAllEntriesPartial(prefix)

        assertEquals(LIST_PAGE_SIZE.toInt(), listing.entries.size)
        assertTrue(listing.failure != null)
        assertFalse(listing.isComplete)
    }
}

/** Mirrors `MAX_LIST_PAGES`, which is private to the paging file. */
private const val MAX_LIST_PAGES_FOR_TEST = 64
