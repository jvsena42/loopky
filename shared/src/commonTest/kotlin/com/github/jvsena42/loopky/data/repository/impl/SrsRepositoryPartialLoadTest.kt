package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.storage.PendingReview
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeBackgroundTasks
import com.github.jvsena42.loopky.testing.FakeDeckCacheStore
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakePendingReviewStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.FakeSettingsRepository
import com.github.jvsena42.loopky.testing.FakeStudyProgressStore
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Review state that did not arrive whole, and the writes that must not paper over it (#494). */
class SrsRepositoryPartialLoadTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val deckRepo = DeckRepositoryImpl(
        pubky = pubky,
        session = session,
        cardRepo = cardRepo,
        revalidator = revalidator,
        tagRepo = RecordingTagRepository(),
        mediaRepo = FakeMediaRepository(),
        backgroundTasks = FakeBackgroundTasks(),
        deckCache = FakeDeckCacheStore(),
        preferences = FakeAppPreferences(),
        scope = CoroutineScope(SupervisorJob()),
    )
    private val journal = FakePendingReviewStore()
    private val settings = FakeSettingsRepository()
    private val progressStore = FakeStudyProgressStore()
    private val repo = SrsRepositoryImpl(
        pubky = pubky,
        session = session,
        revalidator = revalidator,
        deckRepository = deckRepo,
        cardRepository = cardRepo,
        pendingReviews = journal,
        settingsRepository = settings,
        studyProgress = progressStore,
        // The default scope runs `flushAsync` on Dispatchers.Default, where it wrote to
        // FakePubkyClient's plain map while the test thread was listing it (a
        // ConcurrentModificationException in CI). Inline, the flush finishes before the call returns.
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private suspend fun publishDeck(deckId: String, vararg cardIds: String) {
        val cards = cardIds.map { testCard(it, deckId = deckId) }
        deckRepo.publish(testDeck(id = deckId), cards).getOrThrow()
    }

    /** A second process over the same homeserver: an empty cache, so everything is read again. */
    private fun freshRepo(
        journal: FakePendingReviewStore = FakePendingReviewStore(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ) = SrsRepositoryImpl(
        pubky = pubky,
        session = session,
        revalidator = revalidator,
        deckRepository = deckRepo,
        cardRepository = cardRepo,
        pendingReviews = journal,
        settingsRepository = settings,
        studyProgress = progressStore,
        scope = scope,
    )

    @Test
    fun aChunkThatFailsToLoadLeavesTheDeckUnloadedAndTheNextCallReadsItAgain() = runTest {
        publishDeck("deck1", "c1", "c2")
        repo.dueForDeck("deck1")
        repo.review(testCard("c1", deckId = "deck1"), SrsGrade.Easy).getOrThrow()
        repo.flush().getOrThrow()
        val later = freshRepo()

        pubky.failGetWhenUrlContains = "/pub/loopky/srs/"
        later.countsForDeck("deck1")

        // The states that did not arrive are missing, and the repository says so rather than
        // letting the deck pass for one with nothing studied.
        assertFalse(later.isStateLoaded("deck1"))
        assertTrue(later.statesForDeck("deck1").isEmpty())

        pubky.failGetWhenUrlContains = null
        later.countsForDeck("deck1")

        assertTrue(later.isStateLoaded("deck1"))
        assertEquals(setOf("c1"), later.statesForDeck("deck1").keys)
    }

    @Test
    fun aChunkReadThatFailsOnceIsRetriedWithinTheSameLoad() = runTest {
        publishDeck("deck1", "c1", "c2")
        repo.dueForDeck("deck1")
        repo.review(testCard("c1", deckId = "deck1"), SrsGrade.Easy).getOrThrow()
        repo.flush().getOrThrow()
        val later = freshRepo()

        pubky.failGetWhenUrlContains = "/pub/loopky/srs/"
        pubky.failGetTimes = 1
        later.countsToday(listOf(requireNotNull(deckRepo.getLocal("deck1"))))

        assertTrue(later.isStateLoaded("deck1"))
        assertEquals(setOf("c1"), later.statesForDeck("deck1").keys)
    }

    @Test
    fun aGradeWhileReviewStateCannotLoadIsRefusedAndTheHistoryIsKept() = runTest {
        publishDeck("deck1", "c1", "c2")
        repo.dueForDeck("deck1")
        repo.review(testCard("c1", deckId = "deck1"), SrsGrade.Easy).getOrThrow()
        repo.flush().getOrThrow()
        val stored = repo.stateFor("deck1", "c1")
        val later = freshRepo()

        pubky.failGetWhenUrlContains = "/pub/loopky/srs/"
        later.countsForDeck("deck1")
        // c1 reads as never seen here; grading it would schedule it from nothing and the flush
        // would write that over its history.
        val refused = later.review(testCard("c1", deckId = "deck1"), SrsGrade.Again).exceptionOrNull()

        assertIs<ReviewStateUnavailableException>(refused)
        assertEquals(ErrorReason.Offline, refused.toErrorReason())
        pubky.failGetWhenUrlContains = null
        assertEquals(stored, freshRepo().also { it.countsForDeck("deck1") }.stateFor("deck1", "c1"))
    }

    @Test
    fun aGradeAfterAPartialLoadIsScheduledFromTheCardsRealHistory() = runTest {
        publishDeck("deck1", "c1", "c2")
        repo.dueForDeck("deck1")
        repo.review(testCard("c1", deckId = "deck1"), SrsGrade.Easy).getOrThrow()
        repo.review(testCard("c2", deckId = "deck1"), SrsGrade.Easy).getOrThrow()
        repo.flush().getOrThrow()
        val before = requireNotNull(repo.stateFor("deck1", "c1"))
        val later = freshRepo()

        pubky.failGetWhenUrlContains = "/pub/loopky/srs/"
        later.countsForDeck("deck1")
        pubky.failGetWhenUrlContains = null
        val graded = later.review(testCard("c1", deckId = "deck1"), SrsGrade.Good).getOrThrow()
        later.flush().getOrThrow()

        assertEquals(before.repetitions + 1, graded.repetitions)
        val reread = freshRepo().also { it.countsForDeck("deck1") }.statesForDeck("deck1")
        assertEquals(setOf("c1", "c2"), reread.keys)
    }

    @Test
    fun aJournalOnADeckThatCannotLoadWaitsWithoutBlockingAnotherDeck() = runTest {
        publishDeck("deck1", "c1", "c2")
        publishDeck("deck2", "d1")
        repo.dueForDeck("deck1")
        repo.review(testCard("c1", deckId = "deck1"), SrsGrade.Easy).getOrThrow()
        repo.flush().getOrThrow()
        // A previous process graded c2 and died before the flush.
        val journal = FakePendingReviewStore().apply {
            seed(
                PendingReview(
                    ownerPubky = TEST_PUBKY,
                    authorPubky = TEST_PUBKY,
                    deckId = "deck1",
                    chunk = 0,
                    cardId = "c2",
                    dueAt = 1L,
                    intervalDays = 1,
                    easeFactor = 2.5,
                    repetitions = 1,
                ),
            )
        }
        // On the test scheduler, so the restore's own flush — and its retry backoff — finishes
        // before the test goes on.
        val later = freshRepo(journal, scope = backgroundScope)

        pubky.failGetWhenUrlContains = "/srs/$TEST_PUBKY/deck1/"
        later.dueForDeck("deck2")
        advanceUntilIdle()
        later.review(testCard("d1", deckId = "deck2"), SrsGrade.Good).getOrThrow()

        // deck1's chunk was not read, so writing it from the cache would leave c2 alone in it.
        assertIs<ReviewStateUnavailableException>(later.flush().exceptionOrNull())
        assertTrue(pubky.store.keys.any { it.contains("/srs/$TEST_PUBKY/deck2/") })
        assertTrue(journal.entries.any { it.cardId == "c2" })

        pubky.failGetWhenUrlContains = null
        later.flush().getOrThrow()

        val reread = freshRepo().also { it.countsForDeck("deck1") }.statesForDeck("deck1")
        assertEquals(setOf("c1", "c2"), reread.keys)
    }

    @Test
    fun aListingThatFailsIsNotADeckWithNothingStudied() = runTest {
        publishDeck("deck1", "c1")
        repo.dueForDeck("deck1")
        repo.review(testCard("c1", deckId = "deck1"), SrsGrade.Easy).getOrThrow()
        repo.flush().getOrThrow()
        val later = freshRepo()

        pubky.failListWhenUrlContains = "/pub/loopky/srs/"
        later.countsForDeck("deck1")
        assertFalse(later.isStateLoaded("deck1"))

        pubky.failListWhenUrlContains = null
        later.countsForDeck("deck1")
        assertEquals(setOf("c1"), later.statesForDeck("deck1").keys)
    }

    @Test
    fun aDeckNeverStudiedLoadsWholeWithNothingInIt() = runTest {
        publishDeck("deck1", "c1")

        repo.countsForDeck("deck1")

        assertTrue(repo.isStateLoaded("deck1"))
        assertTrue(repo.statesForDeck("deck1").isEmpty())
    }
}
