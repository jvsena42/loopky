package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deck detail with nobody signed in (#150).
 *
 * A shared deck link is the commonest way into Loopky from outside, and it lands here. The whole
 * screen therefore has to render off public records, with the account asked for only by the two
 * things that write — and asked for *by them*, at the moment they are reached.
 *
 * Its own class rather than more cases on [DeckDetailViewModelTest], which is already at detekt's
 * class-size ceiling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckDetailGuestTest {

    private val identityRepo = FakeIdentityRepository(session = null)
    private val deckRepo = FakeDeckRepository()
    private val cardRepo = FakeCardRepository()
    private val discoveryRepo = FakeDiscoveryRepository()

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = DeckDetailViewModel(
        deckId = "deck1",
        authorPubky = "someone-else",
        deckRepository = deckRepo,
        cardRepository = cardRepo,
        identityRepository = identityRepo,
        srsRepository = FakeSrsRepository(),
        mediaRepository = FakeMediaRepository(),
        tagRepository = RecordingTagRepository(),
        discoveryRepository = discoveryRepo,
        appPreferences = FakeAppPreferences(),
    )

    private fun seed(cards: Int = 2) {
        deckRepo.decks["deck1"] =
            testDeck(id = "deck1", authorPubky = "someone-else", cardCount = cards)
        repeat(cards) { i ->
            cardRepo.seed(testCard("c$i", deckId = "deck1", ord = i.toLong()))
        }
    }

    /**
     * A signed-out visitor gets the whole deck: the manifest and the cards are public records, and
     * refusing to render them would make every shared link a sign-in wall.
     */
    @Test
    fun `renders a deck with nobody signed in and offers a preview instead of study`() =
        runTest(mainDispatcher) {
            seed(cards = 2)

            val vm = viewModel()
            advanceUntilIdle()

            val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
            assertFalse(state.isSignedIn)
            assertFalse(state.isOwned)
            assertEquals(2, state.cardPreviews.size)
            assertTrue(state.canPreview)
        }

    @Test
    fun `following a deck with no account raises the prompt instead of writing`() =
        runTest(mainDispatcher) {
            seed(cards = 1)

            val vm = viewModel()
            advanceUntilIdle()
            vm.onToggleFollow()
            advanceUntilIdle()

            val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
            assertEquals(SignInReason.FollowDeck, state.signInPrompt)
            // The optimistic flip must not have happened: nothing was written, so nothing changed.
            assertFalse(state.isFollowing)
            assertTrue(deckRepo.followedDecks.isEmpty())

            vm.onDismissSignInPrompt()
            assertNull(assertIs<DeckDetailUiState.Content>(vm.state.value).signInPrompt)
        }

    @Test
    fun `reaching for edit with no account raises the prompt instead of the confirm dialog`() =
        runTest(mainDispatcher) {
            seed(cards = 1)

            val vm = viewModel()
            advanceUntilIdle()
            // Edit is the only way to a copy now (#254), and a copy is written under a pubky a
            // guest does not have.
            vm.onEditClick()
            advanceUntilIdle()

            val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
            assertEquals(SignInReason.CloneDeck, state.signInPrompt)
            assertFalse(state.showCloneConfirm)
        }

    /** Study is what a kept deck earns; a preview is what an unkept one offers instead. */
    @Test
    fun `study on an unkept deck previews it`() = runTest(mainDispatcher) {
        seed(cards = 1)

        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<DeckDetailEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }
        advanceUntilIdle()

        vm.onStudyClick()
        advanceUntilIdle()
        job.cancel()

        assertEquals<List<DeckDetailEffect>>(listOf(DeckDetailEffect.NavigateStudyPreview), effects)
    }
}
