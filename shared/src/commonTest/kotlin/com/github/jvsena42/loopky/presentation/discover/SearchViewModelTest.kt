package com.github.jvsena42.loopky.presentation.discover

import com.github.jvsena42.loopky.data.pubky.PubkyLink
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.SIGN_IN_LINK
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val discovery = FakeDiscoveryRepository()
    private val identity = FakeIdentityRepository()

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SearchViewModel(
        discoveryRepository = discovery,
        identityRepository = identity,
    )

    private fun person(pubky: String, name: String) = PubkyIdentity(pubky, name, null, null)

    // ── debounce ─────────────────────────────────────────────────────────

    @Test
    fun typingDoesNotSearchUntilTheTypingStops() = runTest {
        discovery.peopleByQuery = mapOf("ada" to listOf(person("adapk", "Ada")))
        val vm = viewModel()

        "ada".forEachIndexed { i, _ -> vm.onQueryChange("ada".take(i + 1)) }
        advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS - 1)

        assertTrue(discovery.peopleQueries.isEmpty(), "searched mid-word: ${discovery.peopleQueries}")
        assertTrue(vm.state.value.isSearching)

        advanceUntilIdle()

        // One query for the settled word, not one per keystroke.
        assertEquals(listOf("ada"), discovery.peopleQueries)
        assertEquals(listOf("adapk"), vm.state.value.people.map { it.identity.pubky })
        assertFalse(vm.state.value.isSearching)
    }

    @Test
    fun backspacingToTheSameWordDoesNotSearchTwice() = runTest {
        val vm = viewModel()

        vm.onQueryChange("ada")
        advanceUntilIdle()
        vm.onQueryChange("adax")
        vm.onQueryChange("ada")
        advanceUntilIdle()

        assertEquals(listOf("ada"), discovery.peopleQueries)
    }

    @Test
    fun aQueryTooShortToNarrowAnythingIsNotSent() = runTest {
        val vm = viewModel()

        vm.onQueryChange("a")
        advanceUntilIdle()

        assertTrue(discovery.peopleQueries.isEmpty())
        assertFalse(vm.state.value.isSearching)
        assertFalse(vm.state.value.hasSearched)
    }

    @Test
    fun resultsAreDroppedTheMomentTheQueryChanges() = runTest {
        discovery.peopleByQuery = mapOf("ada" to listOf(person("adapk", "Ada")))
        val vm = viewModel()

        vm.onQueryChange("ada")
        advanceUntilIdle()
        assertEquals(expected = 1, actual = vm.state.value.people.size)

        vm.onQueryChange("adam")

        // Answers to a question that is no longer on screen would read as answers to this one.
        assertTrue(vm.state.value.people.isEmpty())
        assertFalse(vm.state.value.hasSearched)
    }

    // ── the three answers ────────────────────────────────────────────────

    @Test
    fun searchFindsPeopleAndDecks() = runTest {
        discovery.peopleByQuery = mapOf("spanish" to listOf(person("adapk", "Ada")))
        discovery.decksByQuery = mapOf(
            "spanish" to listOf(testDeck(id = "d1", authorPubky = "adapk", title = "Spanish verbs")),
        )
        val vm = viewModel()

        vm.onQueryChange("spanish")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf("adapk"), state.people.map { it.identity.pubky })
        assertEquals(listOf("d1"), state.decks.map { it.id })
        assertTrue(state.hasSearched)
        assertFalse(state.isEmpty)
    }

    @Test
    fun aPastedPubkyResolvesWithoutWaitingForTheIndexer() = runTest {
        val vm = viewModel()

        vm.onQueryChange(PUBKY)

        // No debounce, no round trip: the text already names the account, which is the only thing
        // that works for someone no index has seen yet.
        assertEquals(PubkyLink.Profile(PUBKY), vm.state.value.directLink)
        advanceUntilIdle()
        assertTrue(discovery.peopleQueries.isEmpty(), "searched for an address")
    }

    @Test
    fun aPastedDeckLinkOpensTheDeckRatherThanItsAuthor() = runTest {
        val vm = viewModel()
        val effects = mutableListOf<SearchEffect>()
        val collector = launch { vm.effects.toList(effects) }

        vm.onQueryChange("pubky://$PUBKY/pub/loopky/decks/deck1/manifest.json")
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(listOf<SearchEffect>(SearchEffect.OpenDeck(PUBKY, "deck1")), effects)
        collector.cancel()
    }

    @Test
    fun submittingFreeTextGoesNowhere() = runTest {
        val vm = viewModel()
        val effects = mutableListOf<SearchEffect>()
        val collector = launch { vm.effects.toList(effects) }

        vm.onQueryChange("just some text")
        vm.onSubmit()
        advanceUntilIdle()

        // There is nothing to open — the results below are the answer.
        assertTrue(effects.isEmpty())
        collector.cancel()
    }

    @Test
    fun aSettledQueryWithNothingBehindItIsEmptyRatherThanBlank() = runTest {
        val vm = viewModel()

        vm.onQueryChange("zzzqqq")
        advanceUntilIdle()

        assertTrue(vm.state.value.isEmpty)
    }

    @Test
    fun searchIsNotEmptyBeforeItHasBeenAsked() = runTest {
        val vm = viewModel()

        advanceUntilIdle()

        // Nothing typed is not "no matches" — the difference is what the screen draws.
        assertFalse(vm.state.value.isEmpty)
    }

    @Test
    fun theScreenSaysItIsSearchingWhileTheQueryIsInFlight() = runTest {
        discovery.searchGate = CompletableDeferred()
        val vm = viewModel()

        vm.onQueryChange("ada")
        advanceUntilIdle()

        assertTrue(vm.state.value.isSearching)
        discovery.searchGate?.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.isSearching)
    }

    // ── people results ───────────────────────────────────────────────────

    @Test
    fun someoneYouAlreadyFollowIsShownAsFollowed() = runTest {
        discovery.follows.add("adapk")
        discovery.peopleByQuery = mapOf("ada" to listOf(person("adapk", "Ada")))
        val vm = viewModel()

        vm.onQueryChange("ada")
        advanceUntilIdle()

        assertTrue(vm.state.value.people.single().isFollowing)
    }

    @Test
    fun followingFromAResultFlipsThePillImmediately() = runTest {
        discovery.peopleByQuery = mapOf("ada" to listOf(person("adapk", "Ada")))
        val vm = viewModel()
        vm.onQueryChange("ada")
        advanceUntilIdle()

        vm.onFollowToggle("adapk")

        assertTrue(vm.state.value.people.single().isFollowing)
        advanceUntilIdle()
        assertTrue(discovery.follows.contains("adapk"))
        assertFalse(vm.state.value.people.single().isFollowPending)
    }

    @Test
    fun aFailedFollowRevertsThePillAndSaysWhy() = runTest {
        discovery.peopleByQuery = mapOf("ada" to listOf(person("adapk", "Ada")))
        discovery.followError = IllegalStateException("offline")
        val vm = viewModel()
        val effects = mutableListOf<SearchEffect>()
        val collector = launch { vm.effects.toList(effects) }
        vm.onQueryChange("ada")
        advanceUntilIdle()

        vm.onFollowToggle("adapk")
        advanceUntilIdle()

        assertFalse(vm.state.value.people.single().isFollowing)
        assertTrue(effects.any { it is SearchEffect.ShowFollowError })
        collector.cancel()
    }

    // ── deck results ─────────────────────────────────────────────────────

    @Test
    fun deckAuthorNamesLandAfterTheResultsDo() = runTest {
        discovery.decksByQuery = mapOf(
            "spanish" to listOf(
                testDeck(id = "d1", authorPubky = "adapk", title = "Spanish", tags = listOf(Tag("spanish"))),
            ),
        )
        identity.profiles["adapk"] = person("adapk", "Ada")
        val vm = viewModel()

        vm.onQueryChange("spanish")
        advanceUntilIdle()

        assertEquals("Ada", vm.state.value.decks.single().author.displayName)
    }

    @Test
    fun clearingTheBoxEmptiesTheScreen() = runTest {
        discovery.peopleByQuery = mapOf("ada" to listOf(person("adapk", "Ada")))
        val vm = viewModel()
        vm.onQueryChange("ada")
        advanceUntilIdle()

        vm.onClearQuery()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(expected = "", actual = state.query)
        assertTrue(state.people.isEmpty())
        assertNull(state.directLink)
        assertFalse(state.isEmpty)
    }

    private companion object {
        /** A real-shaped pubky: 52 z-base-32 characters. */
        const val PUBKY = "ybndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1u"
    }

    // ── sign-in links ────────────────────────────────────────────────────

    @Test
    fun aScannedSignInLinkIsNamedAndNeverSearched() = runTest {
        val vm = viewModel()

        vm.onQueryChange(SIGN_IN_LINK)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.isSignInLink)
        assertNull(state.directLink)
        assertFalse(state.isSearching)
        assertFalse(state.hasSearched)
        assertFalse(state.isEmpty)
        assertTrue(discovery.peopleQueries.isEmpty(), "sent to the indexer: ${discovery.peopleQueries}")
    }

    @Test
    fun clearingASignInLinkGoesBackToAnOrdinaryQuery() = runTest {
        val vm = viewModel()
        vm.onQueryChange(SIGN_IN_LINK)
        vm.onQueryChange("ada")
        advanceUntilIdle()

        assertFalse(vm.state.value.isSignInLink)
        assertEquals(listOf("ada"), discovery.peopleQueries)
    }

    @Test
    fun gettingPubkyRingOpensItsInstallPage() = runTest {
        val vm = viewModel()
        val effects = mutableListOf<SearchEffect>()
        val collector = launch { vm.effects.toList(effects) }
        advanceUntilIdle()

        vm.onGetPubkyRing()
        advanceUntilIdle()

        assertEquals(listOf<SearchEffect>(SearchEffect.OpenInstallPage("https://pubkyring.app")), effects)
        collector.cancel()
    }
}
