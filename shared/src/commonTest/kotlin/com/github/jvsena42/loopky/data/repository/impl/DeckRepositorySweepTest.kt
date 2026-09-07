package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeBackgroundTasks
import com.github.jvsena42.loopky.testing.FakeDeckCacheStore
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import com.github.jvsena42.loopky.testing.testDeckWithCards
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Re-hosting on first fetch (#65) only reaches blobs the user actually looked at. This is the pass
 * that walks the rest, so a clone becomes fully self-contained rather than partially (#53).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckRepositorySweepTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val media = FakeMediaRepository()
    private val backgroundTasks = FakeBackgroundTasks()

    private fun TestScope.repo() = DeckRepositoryImpl(
        pubky = pubky,
        session = session,
        cardRepo = cardRepo,
        revalidator = revalidator,
        tagRepo = RecordingTagRepository(),
        mediaRepo = media,
        backgroundTasks = backgroundTasks,
        deckCache = FakeDeckCacheStore(),
        scope = CoroutineScope(
            backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler),
        ),
    )

    private fun pinnedImage(sha: String) = MediaRef.Image(
        path = "media/$sha.jpg",
        mime = "image/jpeg",
        sha256 = sha,
        width = null,
        height = null,
        uri = "pubky://friendpk/pub/loopky/decks/orig/media/$sha.jpg",
    )

    private fun cardWith(id: String, ref: MediaRef.Image?): Card =
        testCard(id, deckId = "orig").copy(back = CardSide(text = "b$id", imageRef = ref))

    private fun putRemoteDeck(cards: List<Card>, cover: MediaRef.Image? = null): Deck {
        val deck = testDeckWithCards(cards, id = "orig", authorPubky = "friendpk")
            .copy(coverImageRef = cover)
        val root = "pubky://friendpk/pub/loopky/decks/orig"
        pubky.store["$root/manifest.json"] = loopkyJson.encodeToString(deck.toDto())
        cards.chunked(CHUNK_SIZE).forEachIndexed { n, batch ->
            pubky.store["$root/cards/$n.json"] = loopkyJson.encodeToString(
                CardChunkDto(deck_id = "orig", chunk = n, cards = batch.map { it.toDto() }),
            )
        }
        return deck
    }

    private suspend fun TestScope.clonedDeck(
        cards: List<Card>,
        cover: MediaRef.Image? = null,
    ): Pair<DeckRepositoryImpl, Deck> {
        val repo = repo()
        putRemoteDeck(cards, cover)
        val clone = repo.clone(repo.fetchRemote("friendpk", "orig").getOrThrow(), "My copy").getOrThrow()
        media.rehosts.clear()
        return repo to clone
    }

    private suspend fun storedCards(deck: Deck): List<Card> =
        CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined).fetchByDeck(deck).getOrThrow()

    // ── a whole clone ────────────────────────────────────────────────────

    @Test
    fun sweepClearsEveryPinnedRefAndMarksTheDeckDone() = runTest {
        val (repo, clone) = clonedDeck(
            (1..3).map { cardWith("c$it", pinnedImage("sha$it")) },
            cover = pinnedImage("cover"),
        )

        val outcome = repo.rehostPendingMedia(clone.id).getOrThrow()

        assertTrue(outcome.complete)
        assertEquals(4, outcome.rehosted, "3 card blobs + the cover")
        val deck = repo.fetchRemote(TEST_PUBKY, clone.id).getOrThrow()
        assertTrue(deck.mediaRehosted)
        assertEquals(null, deck.coverImageRef?.uri)
        assertTrue(storedCards(deck).all { it.back.imageRef.isRehostedNotDropped() })
    }

    @Test
    fun sweepCopiesABlobSharedAcrossCardsOnce() = runTest {
        val (repo, clone) = clonedDeck((1..3).map { cardWith("c$it", pinnedImage("shared")) })

        repo.rehostPendingMedia(clone.id).getOrThrow()

        assertEquals(1, media.rehosts.size)
        assertTrue(storedCards(repo.getLocal(clone.id)!!).all { it.back.imageRef.isRehostedNotDropped() })
    }

    @Test
    fun sweepCopiesABlobSharedAcrossChunksOnce() = runTest {
        // The same blob at both ends of a two-chunk deck. Within one chunk the refs are deduped
        // as they are collected; across chunks only the pass-level record prevents a second copy.
        val cards = listOf(cardWith("first", pinnedImage("shared"))) +
            (1..CHUNK_SIZE - 1).map { cardWith("filler$it", null) } +
            cardWith("second", pinnedImage("shared"))
        val (repo, clone) = clonedDeck(cards)

        repo.rehostPendingMedia(clone.id).getOrThrow()

        assertEquals(1, media.rehosts.size)
        // Cloning assigns fresh card ids, so the two ends are identified by their text. Counting
        // them is the point: a re-host that dropped the ref instead of rewriting it would leave
        // zero cards carrying an image, which "every ref has a null uri" would happily accept.
        val stored = storedCards(repo.getLocal(clone.id)!!)
        val carrying = stored.filter { it.back.text in setOf("bfirst", "bsecond") }
        assertEquals(2, carrying.size)
        assertTrue(carrying.all { it.back.imageRef.isRehostedNotDropped() })
    }

    @Test
    fun aSweptDeckIsNotSweptAgain() = runTest {
        val (repo, clone) = clonedDeck(listOf(cardWith("c1", pinnedImage("sha1"))))
        repo.rehostPendingMedia(clone.id).getOrThrow()
        media.rehosts.clear()

        val outcome = repo.rehostPendingMedia(clone.id).getOrThrow()

        assertTrue(outcome.complete)
        assertEquals(0, outcome.chunksScanned, "re-read a deck already marked done")
        assertTrue(media.rehosts.isEmpty())
    }

    // ── resuming ─────────────────────────────────────────────────────────

    @Test
    fun sweepStopsAtTheChunkBudgetAndRecordsACursor() = runTest {
        // Three chunks, media only in the last one.
        val cards = (1..CHUNK_SIZE * 2).map { cardWith("c$it", null) } +
            cardWith("last", pinnedImage("late"))
        val (repo, clone) = clonedDeck(cards)

        val first = repo.rehostPendingMedia(clone.id, maxChunks = 1).getOrThrow()

        assertFalse(first.complete)
        assertEquals(1, first.chunksScanned)
        assertEquals(0, first.rehosted, "the blob is in a chunk this pass never reached")
        assertEquals(1, repo.fetchRemote(TEST_PUBKY, clone.id).getOrThrow().mediaRehostCursor)
    }

    @Test
    fun sweepResumesFromTheCursorRatherThanRescanningCleanChunks() = runTest {
        val cards = (1..CHUNK_SIZE * 2).map { cardWith("c$it", null) } +
            cardWith("last", pinnedImage("late"))
        val (repo, clone) = clonedDeck(cards)

        // Without a persisted cursor each budgeted run restarts at chunk 0, so a deck with more
        // chunks than the budget never reaches its tail — the blob in chunk 2 is never copied.
        repeat(3) { repo.rehostPendingMedia(clone.id, maxChunks = 1).getOrThrow() }

        val deck = repo.fetchRemote(TEST_PUBKY, clone.id).getOrThrow()
        assertTrue(deck.mediaRehosted, "cursor=${deck.mediaRehostCursor}")
        assertEquals(1, media.rehosts.size)
    }

    // ── failure handling ─────────────────────────────────────────────────

    @Test
    fun aDeletedOriginDoesNotBlockCompletion() = runTest {
        val (repo, clone) = clonedDeck(listOf(cardWith("c1", pinnedImage("gone"))))
        media.failRehostWith = PubkyError("404 Not Found")

        val outcome = repo.rehostPendingMedia(clone.id).getOrThrow()

        // The blob is not coming back. Leaving the deck pending would re-sweep it forever, on
        // every device, for nothing.
        assertEquals(1, outcome.missing)
        assertTrue(outcome.complete)
        assertTrue(repo.fetchRemote(TEST_PUBKY, clone.id).getOrThrow().mediaRehosted)
        val ref = storedCards(repo.getLocal(clone.id)!!).single().back.imageRef
        assertEquals("pubky://friendpk/pub/loopky/decks/orig/media/gone.jpg", ref?.uri)
    }

    @Test
    fun aTransientFailureLeavesTheDeckPending() = runTest {
        val (repo, clone) = clonedDeck(listOf(cardWith("c1", pinnedImage("sha1"))))
        media.failRehostWith = PubkyError("503 Service Unavailable")

        val outcome = repo.rehostPendingMedia(clone.id).getOrThrow()

        assertEquals(1, outcome.failed)
        assertFalse(outcome.complete)
        assertFalse(repo.fetchRemote(TEST_PUBKY, clone.id).getOrThrow().mediaRehosted)
    }

    @Test
    fun aFullQuotaEndsTheSweepInsteadOfBeingReportedAsUnfinished() = runTest {
        val cards = (1..3).map { cardWith("c$it", pinnedImage("sha$it")) }
        val (repo, clone) = clonedDeck(cards)
        media.failRehostWith = PubkyError("Request failed: 507 Insufficient Storage")

        val result = repo.rehostPendingMedia(clone.id)

        // A failure, not an unfinished outcome: the worker has to be able to tell "come back
        // later" from "stop until the user frees space", and only the exception says the latter.
        assertTrue(result.isFailure)
        assertEquals(ErrorReason.StorageFull, result.exceptionOrNull()!!.toErrorReason())
        // And it stops at the first blob rather than trying the other two into a full disk —
        // re-hosting is itself what consumes the quota.
        assertEquals(1, media.rehosts.size)
        assertFalse(repo.fetchRemote(TEST_PUBKY, clone.id).getOrThrow().mediaRehosted)
    }

    // ── guards ───────────────────────────────────────────────────────────

    @Test
    fun sweepRejectsADeckYouDoNotOwn() = runTest {
        val repo = repo()
        val foreign = putRemoteDeck(listOf(cardWith("c1", pinnedImage("sha1"))))
        repo.fetchRemote("friendpk", "orig").getOrThrow()
        repo.followDeck(foreign).getOrThrow()

        assertTrue(repo.rehostPendingMedia("orig").isFailure)
        assertTrue(media.rehosts.isEmpty())
    }

    @Test
    fun sweepSkipsRemoteImagesAndEmptyShas() = runTest {
        val remote = MediaRef.Image(
            path = "", mime = "image/jpeg", sha256 = "", width = null, height = null,
            url = "https://images.unsplash.com/photo-1",
        )
        val (repo, clone) = clonedDeck(listOf(cardWith("c1", remote)))

        val outcome = repo.rehostPendingMedia(clone.id).getOrThrow()

        // Re-hosting Unsplash bytes would breach their licence, and an empty sha addresses no blob.
        assertEquals(0, outcome.rehosted)
        assertTrue(media.rehosts.isEmpty())
        assertTrue(outcome.complete)
    }

    // ── scheduling ───────────────────────────────────────────────────────

    @Test
    fun cloningSchedulesAMediaSweep() = runTest {
        val repo = repo()
        putRemoteDeck(listOf(cardWith("c1", pinnedImage("sha1"))))
        val before = backgroundTasks.scheduled

        repo.clone(repo.fetchRemote("friendpk", "orig").getOrThrow(), "My copy").getOrThrow()

        // Scheduled from the repository, not a ViewModel: a clone's media is entirely pinned, and
        // no screen should have to remember to ask.
        assertTrue(backgroundTasks.scheduled > before)
    }

    @Test
    fun listingOwnedDecksReschedulesWhileAClonesMediaIsStillPinned() = runTest {
        val (repo, clone) = clonedDeck(listOf(cardWith("c1", pinnedImage("sha1"))))

        // The self-heal: recovers a dropped inline signal or a sweep the system cancelled.
        val before = backgroundTasks.scheduled
        repo.listOwned()
        assertTrue(backgroundTasks.scheduled > before, "no reschedule while media is pinned")

        repo.rehostPendingMedia(clone.id).getOrThrow()
        val after = backgroundTasks.scheduled
        repo.listOwned()
        assertEquals(after, backgroundTasks.scheduled, "kept scheduling for a finished clone")
    }

    @Test
    fun decksPendingRehostReturnsOnlyUnfinishedClones() = runTest {
        val (repo, clone) = clonedDeck(listOf(cardWith("c1", pinnedImage("sha1"))))
        repo.publish(testDeck(id = "mine"), listOf(testCard("x", deckId = "mine"))).getOrThrow()

        assertEquals(listOf(clone.id), repo.decksPendingRehost().map { it.id })

        repo.rehostPendingMedia(clone.id).getOrThrow()
        assertTrue(repo.decksPendingRehost().isEmpty())
    }
}

/**
 * The ref is still there and no longer points at the origin.
 *
 * Not `imageRef?.uri == null`: that is also satisfied by the ref having been dropped altogether,
 * which would let a re-host that deleted a card's image pass as a success.
 */
private fun MediaRef.Image?.isRehostedNotDropped(): Boolean = this != null && uri == null
