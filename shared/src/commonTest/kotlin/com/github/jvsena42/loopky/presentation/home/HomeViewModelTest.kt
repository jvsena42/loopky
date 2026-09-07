package com.github.jvsena42.loopky.presentation.home

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.repository.CachedDecks
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeSettingsRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.fakeSession
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testCoverImage
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val identityRepo = FakeIdentityRepository(session = fakeSession(displayName = "Ana"))
    private val deckRepo = FakeDeckRepository()
    private val srsRepo = FakeSrsRepository()

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val settingsRepo = FakeSettingsRepository()

    private fun viewModel() = HomeViewModel(
        identityRepository = identityRepo,
        deckRepository = deckRepo,
        srsRepository = srsRepo,
        settingsRepository = settingsRepo,
    )

    /** Subscribes eagerly so effects emitted by the init-launched load are not dropped. */
    private fun TestScope.collectEffects(vm: HomeViewModel): List<HomeEffect> {
        val effects = mutableListOf<HomeEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.effects.collect { effects.add(it) }
        }
        return effects
    }

    @Test
    fun contentLoadAggregatesDecksAndDueCounts() = runTest {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            title = "Spanish",
            cardCount = 2,
        )
        deckRepo.decks["deck2"] = testDeck(id = "deck2", title = "Biology")
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"), testCard("c2", deckId = "deck1"))
        srsRepo.seedDue("deck1", "c1", "c2")
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals("Ana", state.identity?.displayName)
        assertEquals(expected = 2, actual = state.dueToday)
        assertEquals(expected = 0, actual = state.doneToday)
        assertEquals(expected = 2, actual = state.decks.size)
        val spanish = state.decks.first { it.id == "deck1" }
        assertEquals(expected = 2, actual = spanish.dueCount)
        assertEquals(expected = 2, actual = spanish.cardCount)
        assertEquals('S', spanish.coverInitial)
        assertEquals(expected = 0, actual = state.decks.first { it.id == "deck2" }.dueCount)
    }

    @Test
    fun theCachedLibraryPaintsBeforeAnythingHasBeenListed() = runTest {
        deckRepo.cached = CachedDecks(
            owned = listOf(testDeck(id = "deck1", title = "Spanish", cardCount = 2)),
            followed = emptyList(),
        )
        deckRepo.listOwnedGate = CompletableDeferred()
        val vm = viewModel()

        advanceUntilIdle()

        // The listing has not answered, and the deck is already on screen.
        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals(listOf("Spanish"), state.decks.map { it.title })
    }

    /**
     * The cached paint knows the decks and not the review state, and "0 due" over a deck with two
     * cards waiting renders as "🎉 You're all caught up" — a congratulation the app takes back a
     * second later.
     */
    @Test
    fun theCachedPaintNeverClaimsYouAreCaughtUp() = runTest {
        deckRepo.cached = CachedDecks(
            owned = listOf(testDeck(id = "deck1", title = "Spanish", cardCount = 2)),
            followed = emptyList(),
        )
        deckRepo.listOwnedGate = CompletableDeferred()
        val vm = viewModel()

        advanceUntilIdle()

        val cachedPaint = assertIs<HomeUiState.Content>(vm.state.value)
        assertFalse(cachedPaint.countsKnown)
        assertFalse(cachedPaint.isCaughtUp)

        // …and once the real counts land, the state says so.
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish", cardCount = 2)
        deckRepo.listOwnedGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(assertIs<HomeUiState.Content>(vm.state.value).countsKnown)
    }

    @Test
    fun aFreshImportIsNotCaughtUpAndDoesNotHeadlineTheWholeDeck() = runTest {
        // #101 §7: 1669 never-seen cards read as "1669 due" and offered an unclimbable wall.
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Biochemistry", cardCount = 200)
        srsRepo.due = (1..200).map { testCard("c$it", deckId = "deck1") }
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals(expected = 0, actual = state.dueToday, "unseen cards are not overdue")
        assertEquals(expected = 200, actual = state.newToday)
        assertFalse(state.isCaughtUp, "a deck you have never opened is not 'all caught up'")
        // The headline is the day's intent, not the backlog.
        assertEquals(expected = state.newCardsGoal, actual = state.studyTarget)
    }

    @Test
    fun theHeadlineCountsRealReviewsOnTopOfTheGoal() = runTest {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardCount = 100)
        srsRepo.due = (1..100).map { testCard("c$it", deckId = "deck1") }
        srsRepo.seedDue("deck1", "c1", "c2", "c3")
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals(expected = 3, actual = state.dueToday)
        assertEquals(expected = 97, actual = state.newToday)
        assertEquals(expected = 3 + state.newCardsGoal, actual = state.studyTarget)
    }

    @Test
    fun pastTheGoalTheHeadlineShowsWhatIsActuallyLeft() = runTest {
        // Clamping to the goal's remaining room would render "0 cards to review" above a Start
        // studying button and a deck row reading "2 new" — a soft goal telling the user to stop.
        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardCount = 2)
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"), testCard("c2", deckId = "deck1"))
        settingsRepo.setStudySettings(StudySettings(newCardsPerDayGoal = 7))
        srsRepo.setDailyProgress(newCards = 10, reviews = 10)
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals(expected = 2, actual = state.newToday)
        assertEquals(expected = 2, actual = state.studyTarget, "the headline hid the remaining cards")
        assertFalse(state.isCaughtUp)
    }

    @Test
    fun emptyDeckListShowsEmptyState() = runTest {
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals("Ana", assertIs<HomeUiState.Empty>(vm.state.value).identity?.displayName)
    }

    @Test
    fun greetingCarriesThePubkyWhenTheresNoDisplayName() = runTest {
        identityRepo.session = fakeSession(pubky = "abcdefgh", displayName = null)
        val vm = viewModel()

        advanceUntilIdle()

        // Naming the user is the platform layer's job — the state only says who they are.
        val identity = assertIs<HomeUiState.Empty>(vm.state.value).identity
        assertEquals("abcdefgh", identity?.pubky)
        assertNull(identity?.displayName)
    }

    @Test
    fun greetingPicksUpANameEditedAfterSignIn() = runTest {
        identityRepo.session = fakeSession(pubky = "abcdefgh", displayName = null)
        identityRepo.profiles["abcdefgh"] =
            PubkyIdentity("abcdefgh", "Cosmic-Crystal-Panda", avatarUrl = null, bio = null)
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(
            "Cosmic-Crystal-Panda",
            assertIs<HomeUiState.Empty>(vm.state.value).identity?.displayName,
        )
    }

    @Test
    fun owningDecksWithNothingDueIsCaughtUpNotEmpty() = runTest {
        // The bug: after finishing a session, Home showed the zero-decks empty state and told a
        // user who owns decks to "create or import a deck".
        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardCount = 1)
        srsRepo.due = emptyList()
        srsRepo.nextDue = 9_999L
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertTrue(state.isCaughtUp)
        assertEquals(9_999L, state.nextDueAtMillis)
    }

    @Test
    fun havingCardsDueIsNotCaughtUp() = runTest {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardCount = 1)
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"))
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertTrue(!state.isCaughtUp)
        assertEquals(null, state.nextDueAtMillis, "next-due lookup should be skipped when cards are due")
    }

    @Test
    fun genericFailureShowsErrorWithoutSigningOut() = runTest {
        deckRepo.listOwnedError = IllegalStateException("electrum hiccup")
        val vm = viewModel()
        val effects = collectEffects(vm)

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Error>(vm.state.value)
        assertEquals(ErrorReason.Unknown, state.reason)
        assertEquals(expected = 0, actual = identityRepo.signOutCount)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun sessionExpiredFailureSignsOutAndNavigatesToOnboarding() = runTest {
        deckRepo.listOwnedError = PubkyError("session expired")
        val vm = viewModel()
        val effects = collectEffects(vm)

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Error>(vm.state.value)
        assertEquals(ErrorReason.SessionExpired, state.reason)
        assertEquals(expected = 1, actual = identityRepo.signOutCount)
        assertEquals(listOf<HomeEffect>(HomeEffect.NavigateToOnboarding), effects)
    }

    @Test
    fun reloadsWhenADeckIsPublishedOrDeleted() = runTest {
        // Reproduces the reported bug: publish a deck, come back to this tab, and it still
        // says you have none because the VM only ever loaded once.
        val vm = viewModel()
        advanceUntilIdle()
        assertIs<HomeUiState.Empty>(vm.state.value)

        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardCount = 1)
        deckRepo.emitChange()
        advanceUntilIdle()

        assertIs<HomeUiState.Content>(vm.state.value)
    }

    @Test
    fun reloadsWhenCardsAreReviewed() = runTest {
        // Reproduces the reported bug: study a deck, come back to Home, and it still shows the
        // due count from before the session because the VM only reloaded on deck changes.
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            cardCount = 2,
        )
        val cards = listOf(testCard("c1", deckId = "deck1"), testCard("c2", deckId = "deck1"))
        srsRepo.due = cards
        srsRepo.seedDue("deck1", "c1", "c2")
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(expected = 2, actual = assertIs<HomeUiState.Content>(vm.state.value).dueToday)

        // Grading empties the queue the way a finished study session does.
        cards.forEach { srsRepo.review(it, SrsGrade.Good) }
        srsRepo.due = emptyList()
        srsRepo.nextDue = 42L
        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals(expected = 0, actual = state.dueToday)
        assertEquals(expected = 0, actual = state.decks.first { it.id == "deck1" }.dueCount)
        assertTrue(state.isCaughtUp)
        assertEquals(expected = 42L, actual = state.nextDueAtMillis)
    }

    @Test
    fun aReviewUpdatesTheCountsWithoutRelistingTheDecks() = runTest {
        // #102: every graded card triggered a full load here — re-listing owned and followed decks,
        // re-syncing each manifest through dueToday(), and re-fetching the user's profile.
        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardCount = 2)
        val cards = listOf(testCard("c1", deckId = "deck1"), testCard("c2", deckId = "deck1"))
        srsRepo.due = cards
        srsRepo.seedDue("deck1", "c1", "c2")
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(expected = 2, actual = assertIs<HomeUiState.Content>(vm.state.value).dueToday)
        val lists = deckRepo.listOwnedCount
        val profileFetches = identityRepo.fetchedProfiles.size

        srsRepo.review(cards.first(), SrsGrade.Good)
        srsRepo.due = listOf(cards.last())
        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals(expected = 1, actual = state.dueToday)
        assertEquals(expected = 1, actual = state.decks.single { it.id == "deck1" }.dueCount)
        assertEquals(expected = lists, actual = deckRepo.listOwnedCount, "re-listed the decks")
        assertEquals(
            expected = profileFetches,
            actual = identityRepo.fetchedProfiles.size,
            "re-fetched the profile",
        )
    }

    @Test
    fun aReviewInADeckHomeCannotSeeLeavesTheOtherCountsAlone() = runTest {
        // dueCountsCached() reports only decks whose state is loaded. A deck it says nothing about
        // has to keep its badge, not silently drop to zero.
        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardCount = 1)
        deckRepo.decks["deck2"] = testDeck(id = "deck2", cardCount = 1)
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"), testCard("c2", deckId = "deck2"))
        srsRepo.seedDue("deck1", "c1")
        srsRepo.seedDue("deck2", "c2")
        val vm = viewModel()
        advanceUntilIdle()

        // Only deck1's cards leave the queue; deck2 is untouched by this session.
        srsRepo.review(testCard("c1", deckId = "deck1"), SrsGrade.Good)
        srsRepo.due = listOf(testCard("c2", deckId = "deck2"))
        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals(expected = 0, actual = state.decks.single { it.id == "deck1" }.dueCount)
        assertEquals(expected = 1, actual = state.decks.single { it.id == "deck2" }.dueCount)
        assertEquals(expected = 1, actual = state.dueToday)
    }

    @Test
    fun aBackgroundReloadDoesNotFlashTheLoadingState() = runTest {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardCount = 1)
        val vm = viewModel()
        advanceUntilIdle()
        assertIs<HomeUiState.Content>(vm.state.value)

        val seen = mutableListOf<HomeUiState>()
        val job = launch { vm.state.collect { seen.add(it) } }
        deckRepo.emitChange()
        advanceUntilIdle()
        job.cancel()

        assertTrue(seen.none { it is HomeUiState.Loading }, "background refresh flashed the loader")
    }

    // ── followed decks (#33) ─────────────────────────────────────────────

    @Test
    fun followedDecksGetARowAndADueBadge() = runTest {
        deckRepo.decks["mine"] = testDeck(id = "mine", title = "Spanish")
        deckRepo.followedDecks["theirs"] = testDeck(
            id = "theirs",
            authorPubky = "friendpk",
            title = "Kanji",
        )
        srsRepo.due = listOf(testCard("t1", deckId = "theirs"))
        srsRepo.seedDue("theirs", "t1")
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals(listOf("mine", "theirs"), state.decks.map { it.id })
        // Home listed owned decks only, so a followed deck's due cards had nowhere to show.
        assertEquals(expected = 1, actual = state.dueToday)
        assertEquals(expected = 1, actual = state.decks.single { it.id == "theirs" }.dueCount)
    }

    @Test
    fun aFollowedDeckAloneIsNotAnEmptyHome() = runTest {
        deckRepo.followedDecks["theirs"] = testDeck(id = "theirs", authorPubky = "friendpk")
        val vm = viewModel()

        advanceUntilIdle()

        assertIs<HomeUiState.Content>(vm.state.value)
    }

    @Test
    fun unreachableFollowedDecksStillLeaveYourOwnOnHome() = runTest {
        deckRepo.decks["mine"] = testDeck(id = "mine")
        deckRepo.listFollowedError = PubkyError("HTTP transport error")
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals(listOf("mine"), state.decks.map { it.id })
    }

    @Test
    fun openingADeckCarriesItsAuthor() = runTest {
        deckRepo.followedDecks["theirs"] = testDeck(id = "theirs", authorPubky = "friendpk")
        val vm = viewModel()
        val effects = collectEffects(vm)
        advanceUntilIdle()

        vm.onDeckClick("theirs")
        advanceUntilIdle()

        // Deck detail cannot fetch a manifest on another homeserver from an id alone.
        assertEquals(listOf(HomeEffect.NavigateDeck("theirs", "friendpk")), effects)
    }

    @Test
    fun nonPubkySessionMessageDoesNotTriggerReauth() = runTest {
        // Same message shape, but not a PubkyError — must not be treated as session expiry.
        deckRepo.listOwnedError = IllegalStateException("session expired")
        val vm = viewModel()
        val effects = collectEffects(vm)

        advanceUntilIdle()

        assertIs<HomeUiState.Error>(vm.state.value)
        assertEquals(expected = 0, actual = identityRepo.signOutCount)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun deckSummaryCarriesTheCoverImage() = runTest(mainDispatcher) {
        val cover = testCoverImage()
        deckRepo.decks["deck1"] = testDeck(id = "deck1", coverImageRef = cover)

        val vm = viewModel()
        advanceUntilIdle()

        // Today's deck rows drew the title initial only; the ref never reached them.
        val summary = assertIs<HomeUiState.Content>(vm.state.value).decks.single()
        assertEquals(cover, summary.coverImage)
    }
}
