package com.github.jvsena42.loopky.presentation.study

import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeSettingsRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.fakeSession
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Previewing a deck nobody has kept: the flip without the scheduler.
 *
 * The thing worth asserting here is what a preview must *not* touch. It runs for a visitor with no
 * session at all, so any [FakeSrsRepository] call it makes would be a "Not signed in" failure in
 * the real app — which is why the SRS fake is left completely unseeded and its counters checked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudySessionPreviewTest {

    private val srsRepo = FakeSrsRepository()
    private val deckRepo = FakeDeckRepository()
    private val cardRepo = FakeCardRepository()
    private val settingsRepo = FakeSettingsRepository()
    private val identityRepo = FakeIdentityRepository(session = null)

    private val discoveryRepo = FakeDiscoveryRepository()
    private val preferences = FakeAppPreferences()

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = StudySessionViewModel(
        deckId = "deck1",
        srsRepository = srsRepo,
        deckRepository = deckRepo,
        cardRepository = cardRepo,
        settingsRepository = settingsRepo,
        identityRepository = identityRepo,
        discoveryRepository = discoveryRepo,
        appPreferences = preferences,
        isPreview = true,
        previewAuthorPubky = "author",
    )

    private fun seed(cardCount: Int) {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", authorPubky = "author", title = "Spanish")
        repeat(cardCount) { i ->
            cardRepo.seed(
                testCard("c$i", front = "front$i", back = "back$i", deckId = "deck1", ord = i.toLong()),
            )
        }
    }

    @Test
    fun `serves the deck's own cards without asking the scheduler`() = runTest(mainDispatcher) {
        seed(cardCount = 3)
        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertTrue(state.isPreview)
        assertEquals(3, state.total)
        assertEquals("Spanish", state.deckTitle)
        // The scheduler was never asked: its queue is empty, so anything that went through
        // dueForDeck would have landed on Empty rather than on three cards.
        assertTrue(srsRepo.due.isEmpty())
    }

    /** A sample, not a session: the point is to reach the end and be asked. */
    @Test
    fun `caps the queue at the preview size`() = runTest(mainDispatcher) {
        seed(cardCount = StudySessionViewModel.PREVIEW_CARDS + 5)
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(
            StudySessionViewModel.PREVIEW_CARDS,
            assertIs<StudySessionUiState.Reviewing>(vm.state.value).total,
        )
    }

    /**
     * The four grade buttons are never on offer, revealed or not — a difficulty chosen for a
     * review that is discarded is a control that lies about what it did.
     */
    @Test
    fun `never offers grades and ignores one that arrives anyway`() = runTest(mainDispatcher) {
        seed(cardCount = 2)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onReveal()
        advanceUntilIdle()
        val revealed = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertTrue(revealed.revealed)
        assertFalse(revealed.gradesAvailable)
        assertTrue(revealed.previewAdvanceAvailable)
        assertTrue(revealed.intervals.isEmpty())

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        assertEquals(0, srsRepo.reviews.size)
        // The card did not move on either: onGrade is inert here, and Next is the only way past.
        assertEquals(1, assertIs<StudySessionUiState.Reviewing>(vm.state.value).position)
    }

    @Test
    fun `next card advances and the last one ends on the offer`() = runTest(mainDispatcher) {
        seed(cardCount = 2)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onNextCard()
        advanceUntilIdle()
        assertEquals(2, assertIs<StudySessionUiState.Reviewing>(vm.state.value).position)

        vm.onNextCard()
        advanceUntilIdle()
        val done = assertIs<StudySessionUiState.Complete>(vm.state.value)
        assertTrue(done.isPreview)
        assertFalse(done.isSignedIn)
        assertEquals(2, done.reviewed)
        // Nothing was buffered, so nothing may be flushed — a flush with no session fails.
        assertEquals(0, srsRepo.flushes)
    }

    private fun TestScope.finish(vm: StudySessionViewModel, cards: Int) {
        repeat(cards) {
            vm.onNextCard()
            advanceUntilIdle()
        }
    }

    @Test
    fun `a guest leaving for sign-in has the deck remembered for a follow`() = runTest(mainDispatcher) {
        seed(cardCount = 1)
        val vm = viewModel()
        advanceUntilIdle()
        finish(vm, cards = 1)
        val effect = backgroundScope.async { vm.effects.first { it is StudySessionEffect.NavigateSignIn } }
        advanceUntilIdle()

        vm.onSignIn()
        advanceUntilIdle()

        assertEquals("author" to "deck1", deckRepo.pendingFollow)
        assertIs<StudySessionEffect.NavigateSignIn>(effect.await())
    }

    @Test
    fun `a signed-in reader is offered Follow and returns to the deck once it lands`() = runTest(mainDispatcher) {
        identityRepo.session = fakeSession()
        // Announcements off: no prompt stands between the follow and the deck.
        preferences.setShareOnPubky(false)
        seed(cardCount = 1)
        val vm = viewModel()
        advanceUntilIdle()
        finish(vm, cards = 1)
        val done = assertIs<StudySessionUiState.Complete>(vm.state.value)
        assertTrue(done.isSignedIn)
        assertTrue(done.canFollow)
        val effect = backgroundScope.async { vm.effects.first { it is StudySessionEffect.Close } }
        advanceUntilIdle()

        vm.onFollowDeck()
        advanceUntilIdle()

        assertTrue(deckRepo.isFollowingDeck("deck1"))
        assertFalse(assertIs<StudySessionUiState.Complete>(vm.state.value).canFollow)
        assertIs<StudySessionEffect.Close>(effect.await())
        // A guest's remember-for-later is not what a signed-in follow goes through.
        assertEquals(null, deckRepo.pendingFollow)
    }

    private suspend fun TestScope.followedWithPrompt(): StudySessionViewModel {
        identityRepo.session = fakeSession()
        seed(cardCount = 1)
        val vm = viewModel()
        advanceUntilIdle()
        finish(vm, cards = 1)
        vm.onFollowDeck()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `a follow from the preview offers to announce it before leaving`() = runTest(mainDispatcher) {
        val effects = mutableListOf<StudySessionEffect>()
        val vm = followedWithPrompt()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()

        val done = assertIs<StudySessionUiState.Complete>(vm.state.value)
        assertTrue(deckRepo.isFollowingDeck("deck1"))
        assertEquals(DeckAnnouncement.Kind.Followed, done.sharePrompt?.kind)
        assertFalse(effects.any { it is StudySessionEffect.Close }, "the screen waits on the answer")

        vm.onShareConfirm()
        advanceUntilIdle()

        assertEquals(listOf("Spanish"), discoveryRepo.announcements.map { it.deckTitle })
        assertTrue(effects.any { it is StudySessionEffect.Shared })
        assertTrue(effects.any { it is StudySessionEffect.Close })
        collector.cancel()
    }

    @Test
    fun `never ask again turns the offer off and leaves without posting`() = runTest(mainDispatcher) {
        val vm = followedWithPrompt()
        val effects = mutableListOf<StudySessionEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()

        vm.onShareNeverAsk()
        advanceUntilIdle()

        assertTrue(discoveryRepo.announcements.isEmpty())
        assertFalse(preferences.shareOnPubkyValue)
        assertTrue(effects.any { it is StudySessionEffect.Close })
        collector.cancel()
    }

    @Test
    fun `a failed announcement still leaves with the follow intact`() = runTest(mainDispatcher) {
        discoveryRepo.announceError = IllegalStateException("offline")
        val vm = followedWithPrompt()
        val effects = mutableListOf<StudySessionEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()

        vm.onShareConfirm()
        advanceUntilIdle()

        assertTrue(deckRepo.isFollowingDeck("deck1"))
        assertTrue(effects.any { it is StudySessionEffect.ShareFailed })
        assertTrue(effects.any { it is StudySessionEffect.Close })
        collector.cancel()
    }

    @Test
    fun `a deck already followed is not offered again`() = runTest(mainDispatcher) {
        identityRepo.session = fakeSession()
        seed(cardCount = 1)
        deckRepo.followDeck(deckRepo.decks.getValue("deck1"))
        val vm = viewModel()
        advanceUntilIdle()
        finish(vm, cards = 1)

        assertFalse(assertIs<StudySessionUiState.Complete>(vm.state.value).canFollow)
    }

    @Test
    fun `a failed follow stays on the offer and says why`() = runTest(mainDispatcher) {
        identityRepo.session = fakeSession()
        seed(cardCount = 1)
        deckRepo.followError = IllegalStateException("boom")
        val vm = viewModel()
        advanceUntilIdle()
        finish(vm, cards = 1)

        vm.onFollowDeck()
        advanceUntilIdle()

        val done = assertIs<StudySessionUiState.Complete>(vm.state.value)
        assertTrue(done.canFollow)
        assertFalse(done.isFollowPending)
        assertTrue(done.followError != null)
    }

    /** Closing must not try to persist reviews that were never taken. */
    @Test
    fun `closing flushes nothing`() = runTest(mainDispatcher) {
        seed(cardCount = 1)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onClose()
        advanceUntilIdle()
        assertEquals(0, srsRepo.flushes)
    }
}
