package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.domain.model.Deck
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A link still reaches a blocked author's deck; the screen shows none of it (#577). */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckDetailBlockedTest {

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
        authorPubky = "trollpk",
        deckRepository = deckRepo,
        cardRepository = cardRepo,
        identityRepository = FakeIdentityRepository(),
        srsRepository = FakeSrsRepository(),
        mediaRepository = FakeMediaRepository(),
        tagRepository = RecordingTagRepository(),
        discoveryRepository = discoveryRepo,
        appPreferences = FakeAppPreferences(),
    )

    private fun seed() {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", authorPubky = "trollpk", cardCount = 1)
        cardRepo.seed(testCard("c0", deckId = "deck1", ord = 0L))
    }

    @Test
    fun `a blocked author's deck is shown as blocked and not as the deck`() = runTest(mainDispatcher) {
        seed()
        discoveryRepo.blocks.add("trollpk")

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(DeckDetailUiState.Blocked, vm.state.value)
    }

    @Test
    fun `the same deck renders when its author is not blocked`() = runTest(mainDispatcher) {
        seed()

        val vm = viewModel()
        advanceUntilIdle()

        assertIs<DeckDetailUiState.Content>(vm.state.value)
    }

    @Test
    fun `a deck by an account a followee reported is hidden until show anyway`() = runTest(mainDispatcher) {
        seed()
        discoveryRepo.reportedByFollowees.add("trollpk")

        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(DeckDetailUiState.HiddenByReport, vm.state.value)

        vm.onShowAnyway()
        advanceUntilIdle()
        assertIs<DeckDetailUiState.Content>(vm.state.value)
    }

    @Test
    fun `report opens the sheet on the deck and a blocking report hides it`() = runTest(mainDispatcher) {
        seed()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onReportClick()
        assertEquals("deck1", assertIs<DeckDetailUiState.Content>(vm.state.value).reportingDeck?.id)

        vm.onReportSent(blocked = true)
        advanceUntilIdle()
        assertEquals(DeckDetailUiState.Blocked, vm.state.value)
    }

    @Test
    fun `a report whose block failed keeps the deck on screen`() = runTest(mainDispatcher) {
        seed()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onReportClick()
        vm.onReportSent(blocked = false)
        advanceUntilIdle()

        assertEquals(null, assertIs<DeckDetailUiState.Content>(vm.state.value).reportingDeck)
    }

    @Test
    fun `a stranger's deck fetches its cards while the report check runs`() = runTest(mainDispatcher) {
        seed()
        val slowCards = object : CardRepository by cardRepo {
            override suspend fun fetchByDeck(deck: Deck) = cardRepo.fetchByDeck(deck).also { delay(SLOW_MS) }
        }
        val slowReports = object : DiscoveryRepository by discoveryRepo {
            override suspend fun isReportedByFollowee(pubky: String) =
                discoveryRepo.isReportedByFollowee(pubky).also { delay(SLOW_MS) }
        }
        val vm = DeckDetailViewModel(
            deckId = "deck1",
            authorPubky = "trollpk",
            deckRepository = deckRepo,
            cardRepository = slowCards,
            identityRepository = FakeIdentityRepository(),
            srsRepository = FakeSrsRepository(),
            mediaRepository = FakeMediaRepository(),
            tagRepository = RecordingTagRepository(),
            discoveryRepository = slowReports,
            appPreferences = FakeAppPreferences(),
        )
        while (vm.state.value !is DeckDetailUiState.Content) {
            mainDispatcher.scheduler.advanceTimeBy(100)
            mainDispatcher.scheduler.runCurrent()
        }

        assertTrue(currentTime < 2 * SLOW_MS, "content after ${currentTime}ms: cards waited for the report check")
    }

    private companion object {
        const val SLOW_MS = 1_000L
    }
}
