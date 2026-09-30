package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
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
import com.github.jvsena42.loopky.testing.testDeckWithCards
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cloning pins a card's media to the original author's blob rather than re-uploading it, which is
 * what keeps cloning an Anki-sized deck instant. Copying the blob under the clone is only half of
 * un-pinning it: without rewriting the record the card keeps its `uri`, reads keep resolving to the
 * origin, and every session re-copies the same blob for nothing (#65).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckRepositoryRehostTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val media = FakeMediaRepository()

    /**
     * The repository, with its `pinnedFetches` collector on an unconfined test dispatcher so it
     * subscribes eagerly and processes a signal as soon as it is emitted. `backgroundScope`'s own
     * dispatcher is not advanced by `advanceUntilIdle`, so the collector would never run; the scope
     * is still parented to it, so the collector is cancelled with the test.
     */
    private fun TestScope.repo() = DeckRepositoryImpl(
        pubky = pubky,
        session = session,
        cardRepo = cardRepo,
        revalidator = revalidator,
        tagRepo = RecordingTagRepository(),
        mediaRepo = media,
        backgroundTasks = FakeBackgroundTasks(),
        deckCache = FakeDeckCacheStore(),
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
    )

    private val origin = "pubky://friendpk/pub/loopky/decks/orig/media"

    private fun pinnedImage(sha: String = "abc") = MediaRef.Image(
        path = "media/$sha.jpg",
        mime = "image/jpeg",
        sha256 = sha,
        width = null,
        height = null,
        uri = "$origin/$sha.jpg",
    )

    private fun cardWith(id: String, ref: MediaRef.Image?, deckId: String = "orig"): Card =
        testCard(id, deckId = deckId).copy(back = CardSide(text = "back of $id", imageRef = ref))

    /** A deck on someone else's homeserver, ready to be cloned. */
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

    /** The cards as they are actually stored, read back through a cold cache. */
    private suspend fun storedCards(deck: Deck): List<Card> =
        CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined).fetchByDeck(deck).getOrThrow()

    // ── the write-back ───────────────────────────────────────────────────

    @Test
    fun rehostBlobClearsTheUriOnTheStoredCard() = runTest {
        val repo = repo()
        putRemoteDeck(listOf(cardWith("c1", pinnedImage())))
        val clone = repo.clone(repo.fetchRemote("friendpk", "orig").getOrThrow(), "My copy").getOrThrow()

        repo.rehostBlob(clone.id, "abc").getOrThrow()

        val ref = assertNotNull(storedCards(repo.getLocal(clone.id)!!).single().back.imageRef)
        assertNull(ref.uri, "the card still points at the original author's blob")
        assertEquals(listOf(clone.id to "abc"), media.rehosts.map { it.first to it.second.sha256 })
    }

    @Test
    fun rehostBlobRewritesTheDeckCover() = runTest {
        val repo = repo()
        putRemoteDeck(listOf(cardWith("c1", null)), cover = pinnedImage("cov"))
        val clone = repo.clone(repo.fetchRemote("friendpk", "orig").getOrThrow(), "My copy").getOrThrow()

        repo.rehostBlob(clone.id, "cov").getOrThrow()

        assertNull(repo.fetchRemote(TEST_PUBKY, clone.id).getOrThrow().coverImageRef?.uri)
    }

    @Test
    fun rehostBlobCopiesOnceForCardsThatShareABlob() = runTest {
        val repo = repo()
        putRemoteDeck((1..3).map { cardWith("c$it", pinnedImage()) })
        val clone = repo.clone(repo.fetchRemote("friendpk", "orig").getOrThrow(), "My copy").getOrThrow()

        repo.rehostBlob(clone.id, "abc").getOrThrow()

        // Content-addressed, so one copy serves every card carrying that sha.
        assertEquals(1, media.rehosts.size)
        assertTrue(storedCards(repo.getLocal(clone.id)!!).all { it.back.imageRef.isRehostedNotDropped() })
    }

    @Test
    fun rehostBlobIsNotAttemptedTwice() = runTest {
        val repo = repo()
        putRemoteDeck(listOf(cardWith("c1", pinnedImage())))
        val clone = repo.clone(repo.fetchRemote("friendpk", "orig").getOrThrow(), "My copy").getOrThrow()

        repo.rehostBlob(clone.id, "abc").getOrThrow()
        val puts = pubky.puts.size
        repo.rehostBlob(clone.id, "abc").getOrThrow()

        // The PUT is idempotent, so re-copying would be silent waste rather than corruption —
        // which is worse, because nobody would notice.
        assertEquals(1, media.rehosts.size)
        assertEquals(puts, pubky.puts.size)
    }

    @Test
    fun aFailedRehostIsNotRetriedForTheRestOfTheSession() = runTest {
        val repo = repo()
        putRemoteDeck(listOf(cardWith("c1", pinnedImage())))
        val clone = repo.clone(repo.fetchRemote("friendpk", "orig").getOrThrow(), "My copy").getOrThrow()
        media.failRehostWith = PubkyError("404 Not Found")

        assertTrue(repo.rehostBlob(clone.id, "abc").isFailure)
        repo.rehostBlob(clone.id, "abc").getOrThrow()

        // The ref is still pinned, so nothing else would stop this being retried on every render
        // of the card. A fresh session, or the deferred sweep, tries again.
        assertEquals(1, media.rehosts.size)
    }

    // ── the guards ───────────────────────────────────────────────────────

    @Test
    fun rehostBlobIsANoOpForADeckYouDoNotOwn() = runTest {
        val repo = repo()
        val foreign = putRemoteDeck(listOf(cardWith("c1", pinnedImage())))
        repo.fetchRemote("friendpk", "orig").getOrThrow()
        repo.followDeck(foreign).getOrThrow()
        // Warm the card cache, so ownership is what stops this rather than there being no cards
        // to look at.
        cardRepo.fetchByDeck(foreign).getOrThrow()

        repo.rehostBlob("orig", "abc").getOrThrow()

        // Copying a followed deck's blobs would write them under your own pubky at a deckId you
        // do not own and cannot edit.
        assertTrue(media.rehosts.isEmpty())
    }

    @Test
    fun rehostBlobIsANoOpWhenNoCachedCardCarriesTheSha() = runTest {
        val repo = repo()
        putRemoteDeck(listOf(cardWith("c1", pinnedImage())))
        val clone = repo.clone(repo.fetchRemote("friendpk", "orig").getOrThrow(), "My copy").getOrThrow()

        repo.rehostBlob(clone.id, "nothing-carries-this").getOrThrow()

        assertTrue(media.rehosts.isEmpty())
    }

    @Test
    fun rehostBlobLeavesTheCardAloneWhenTheOriginIsGone() = runTest {
        val repo = repo()
        putRemoteDeck(listOf(cardWith("c1", pinnedImage())))
        val clone = repo.clone(repo.fetchRemote("friendpk", "orig").getOrThrow(), "My copy").getOrThrow()
        val before = storedCards(repo.getLocal(clone.id)!!)
        media.failRehostWith = PubkyError("404 Not Found")

        assertTrue(repo.rehostBlob(clone.id, "abc").isFailure)

        // A 404 today may be an outage tomorrow, so the ref is left dangling rather than cleared.
        assertEquals(before, storedCards(repo.getLocal(clone.id)!!))
    }

    @Test
    fun rehostBlobDoesNotTouchTheDeckOrNotifyFollowers() = runTest {
        val repo = repo()
        putRemoteDeck(listOf(cardWith("c1", pinnedImage())))
        val clone = repo.clone(repo.fetchRemote("friendpk", "orig").getOrThrow(), "My copy").getOrThrow()
        // A stamp the current clock cannot coincidentally reproduce: publish and re-host land in
        // the same millisecond here, so comparing against the clone's own stamp proves nothing.
        repo.updateMetadata(clone.copy(updatedAt = 1_234L)).getOrThrow()
        var changes = 0
        val watching = backgroundScope.launch { repo.changes.collect { changes++ } }
        advanceUntilIdle()

        repo.rehostBlob(clone.id, "abc").getOrThrow()
        advanceUntilIdle()
        watching.cancel()

        // Re-hosting rewrites refs to blobs the deck already had. Bumping updated_at would tell
        // every follower the author published changes they cannot see, and emitting `changes`
        // would reload the library on every screen showing it.
        assertEquals(1_234L, repo.fetchRemote(TEST_PUBKY, clone.id).getOrThrow().updatedAt)
        assertEquals(0, changes)
    }

    // ── driven by the signal ─────────────────────────────────────────────

    @Test
    fun aPinnedFetchTriggersARehost() = runTest {
        val repo = repo()
        putRemoteDeck(listOf(cardWith("c1", pinnedImage())))
        val clone = repo.clone(repo.fetchRemote("friendpk", "orig").getOrThrow(), "My copy").getOrThrow()

        // What MediaRepository.get emits once it has served the blob to draw the card.
        media.emitPinnedFetch(clone.id, "abc")
        advanceUntilIdle()

        assertEquals(1, media.rehosts.size, "collector never fired")
        assertNull(storedCards(repo.getLocal(clone.id)!!).single().back.imageRef?.uri)
    }
}

/**
 * The ref is still there and no longer points at the origin.
 *
 * Not `imageRef?.uri == null`: that is also satisfied by the ref having been dropped altogether,
 * which would let a re-host that deleted a card's image pass as a success.
 */
private fun MediaRef.Image?.isRehostedNotDropped(): Boolean = this != null && uri == null
