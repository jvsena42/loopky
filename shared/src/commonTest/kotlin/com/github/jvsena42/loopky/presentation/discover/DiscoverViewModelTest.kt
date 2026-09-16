package com.github.jvsena42.loopky.presentation.discover

import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.testCoverImage
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {

    private val discovery = FakeDiscoveryRepository()
    private val tagRepo = RecordingTagRepository()
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

    private fun viewModel() = DiscoverViewModel(
        discoveryRepository = discovery,
        tagRepository = tagRepo,
        identityRepository = identity,
    )

    private fun seedFeed() {
        discovery.feed = listOf(
            testDeck(id = "spanish", authorPubky = "friend1", tags = listOf(Tag("spanish")), updatedAt = 300L),
            testDeck(id = "biology", authorPubky = "friend2", tags = listOf(Tag("biology")), updatedAt = 200L),
        )
    }

    private fun seedGlobal() {
        discovery.globalDecks = listOf(
            testDeck(id = "chess", authorPubky = "stranger1", tags = listOf(Tag("chess"))),
            testDeck(id = "kanji", authorPubky = "stranger2", tags = listOf(Tag("kanji"))),
        )
    }

    // ── the two strips (a deck must not be drawn, or denied, twice) ──────

    @Test
    fun aDeckYouFollowIsNotAlsoDrawnUnderGlobalBrowse() = runTest {
        // Global browse returns every public deck, including the ones whose authors you follow,
        // so the same deck was rendered once in each strip on one screen.
        val shared = testDeck(id = "anatomy", authorPubky = "friend1", tags = listOf(Tag("osteology")))
        discovery.feed = listOf(shared)
        discovery.globalDecks = listOf(shared)
        val vm = viewModel()

        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf("anatomy"), state.following.items.map { it.id })
        assertTrue(state.browseExcludingFollowed.items.isEmpty())
    }

    @Test
    fun browseIsNotCalledEmptyWhenTheFollowStripIsShowingItsDecks() = runTest {
        // The regression: with one deck matching a tag, and that deck followed, browse deduped to
        // nothing and the screen rendered "No decks tagged X yet" directly above the deck it was
        // denying. Empty and covered-by-followed are different states and must stay distinguishable.
        val shared = testDeck(id = "anatomy", authorPubky = "friend1", tags = listOf(Tag("osteology")))
        discovery.feed = listOf(shared)
        discovery.globalDecks = listOf(shared)
        val vm = viewModel()

        advanceUntilIdle()

        assertTrue(vm.state.value.browseFullyCoveredByFollowed)
    }

    @Test
    fun browseIsGenuinelyEmptyWhenNothingMatchedAtAll() = runTest {
        discovery.feed = emptyList()
        discovery.globalDecks = emptyList()
        val vm = viewModel()

        advanceUntilIdle()

        // Nothing to cover, so the empty state is an honest claim and must still be reachable.
        assertFalse(vm.state.value.browseFullyCoveredByFollowed)
        assertTrue(vm.state.value.browseExcludingFollowed.isEmpty)
    }

    @Test
    fun aStrangersDeckSurvivesTheDedupe() = runTest {
        val followed = testDeck(id = "anatomy", authorPubky = "friend1")
        val stranger = testDeck(id = "chess", authorPubky = "stranger1")
        discovery.feed = listOf(followed)
        discovery.globalDecks = listOf(followed, stranger)
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(listOf("chess"), vm.state.value.browseExcludingFollowed.items.map { it.id })
        assertFalse(vm.state.value.browseFullyCoveredByFollowed)
    }

    // ── the dead end (#26) ───────────────────────────────────────────────

    @Test
    fun zeroFollowsStillBrowsesTheNetwork() = runTest {
        discovery.feed = emptyList()
        seedGlobal()
        tagRepo.deckTags = listOf(Tag("chess"))
        val vm = viewModel()

        advanceUntilIdle()

        // The regression this issue is about: following nobody used to collapse the whole screen
        // to "follow a friend to see their decks here".
        val state = vm.state.value
        assertEquals(listOf("chess", "kanji"), state.browse.items.map { it.id })
        assertEquals(listOf(Tag("chess")), state.topics.items)
        assertTrue(state.following.items.isEmpty())
        assertNull(state.following.error)
    }

    @Test
    fun browseAsksForEveryLoopkyDeckWhenNoTagIsSelected() = runTest {
        seedGlobal()
        viewModel()

        advanceUntilIdle()

        assertEquals(
            listOf(ReservedTags.DECK to DiscoverViewModel.BROWSE_LIMIT),
            discovery.globalRequests,
        )
    }

    @Test
    fun followingFailureLeavesTheOtherStripsIntact() = runTest {
        discovery.feedError = IllegalStateException("something unclassifiable")
        seedGlobal()
        tagRepo.deckTags = listOf(Tag("chess"))
        val vm = viewModel()

        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ErrorReason.Unknown, state.following.error)
        // One unreachable strip must not take the screen down with it.
        assertEquals(listOf("chess", "kanji"), state.browse.items.map { it.id })
        assertEquals(listOf(Tag("chess")), state.topics.items)
    }

    @Test
    fun retryingTheFollowedStripDoesNotReBrowse() = runTest {
        discovery.feedError = IllegalStateException("offline")
        seedGlobal()
        val vm = viewModel()
        advanceUntilIdle()
        val browsesAfterLoad = discovery.globalRequests.size

        discovery.feedError = null
        seedFeed()
        vm.onRetryFollowing()
        advanceUntilIdle()

        val state = vm.state.value
        assertNull(state.following.error)
        assertEquals(listOf("spanish", "biology"), state.following.items.map { it.id })
        assertEquals(browsesAfterLoad, discovery.globalRequests.size)
    }

    // ── topic selection goes to the network ──────────────────────────────

    @Test
    fun selectingATopicBrowsesGloballyForThatTopic() = runTest {
        seedFeed()
        discovery.globalDecks = listOf(
            testDeck(id = "otherspanish", authorPubky = "stranger1", tags = listOf(Tag("spanish"))),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTagSelected(Tag("spanish"))
        advanceUntilIdle()

        // The old behaviour filtered the cached feed, so a network-wide topic found nothing.
        assertEquals(Tag("spanish") to DiscoverViewModel.BROWSE_LIMIT, discovery.globalRequests.last())
        assertEquals(listOf("otherspanish"), vm.state.value.browse.items.map { it.id })
    }

    @Test
    fun selectingATopicAlsoNarrowsTheFollowedStripLocally() = runTest {
        seedFeed()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTagSelected(Tag("spanish"))
        advanceUntilIdle()

        assertEquals(listOf("spanish"), vm.state.value.following.items.map { it.id })
    }

    @Test
    fun selectingTheSameTopicAgainClearsTheFilterAndBrowsesEverything() = runTest {
        seedFeed()
        seedGlobal()
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTagSelected(Tag("spanish"))
        advanceUntilIdle()

        vm.onTagSelected(Tag("spanish"))
        advanceUntilIdle()

        assertNull(vm.state.value.selectedTag)
        assertEquals(ReservedTags.DECK, discovery.globalRequests.last().first)
        assertEquals(listOf("spanish", "biology"), vm.state.value.following.items.map { it.id })
    }

    // ── strips are independent ───────────────────────────────────────────

    @Test
    fun aSlowBrowseDoesNotHoldUpTopicsOrTheFollowedStrip() = runTest {
        seedFeed()
        seedGlobal()
        tagRepo.deckTags = listOf(Tag("chess"))
        val gate = CompletableDeferred<Unit>()
        discovery.globalGate = gate
        val vm = viewModel()

        advanceUntilIdle()

        val midFlight = vm.state.value
        assertTrue(midFlight.browse.isLoading, "browse should still be in flight")
        assertFalse(midFlight.topics.isLoading, "topics should not wait on browse")
        assertFalse(midFlight.following.isLoading, "following should not wait on browse")
        assertEquals(listOf("spanish", "biology"), midFlight.following.items.map { it.id })

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("chess", "kanji"), vm.state.value.browse.items.map { it.id })
    }

    @Test
    fun aStaleTopicResultIsDiscarded() = runTest {
        discovery.globalDecks = listOf(
            testDeck(id = "chessdeck", authorPubky = "s1", tags = listOf(Tag("chess"))),
            testDeck(id = "kanjideck", authorPubky = "s2", tags = listOf(Tag("kanji"))),
        )
        val vm = viewModel()
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        discovery.globalGate = gate
        vm.onTagSelected(Tag("chess"))
        vm.onTagSelected(Tag("kanji"))
        gate.complete(Unit)
        advanceUntilIdle()

        // Cancelling the in-flight job can miss a suspension point, so the selection itself has to
        // be the token — otherwise chess's result lands on top of kanji's.
        assertEquals(Tag("kanji"), vm.state.value.selectedTag)
        assertEquals(listOf("kanjideck"), vm.state.value.browse.items.map { it.id })
    }

    // ── topics ───────────────────────────────────────────────────────────

    @Test
    fun topicsMergeGlobalLabelsWithFeedLabelsWithoutDuplicates() = runTest {
        seedFeed()
        tagRepo.deckTags = listOf(Tag("spanish"), Tag("history"))
        val vm = viewModel()

        advanceUntilIdle()

        // Global topics keep their ranking and lead; "spanish" is in both and appears once.
        assertEquals(
            listOf(Tag("spanish"), Tag("history"), Tag("biology")),
            vm.state.value.topics.items,
        )
    }

    @Test
    fun topicsNeverIncludeReservedLabels() = runTest {
        discovery.feed = listOf(
            testDeck(id = "d1", authorPubky = "friend1", tags = listOf(ReservedTags.DECK, Tag("spanish"))),
        )
        tagRepo.deckTags = listOf(ReservedTags.USER, Tag("history"))
        val vm = viewModel()

        advanceUntilIdle()

        // loopky-* is Loopky's index, not a topic — a chip for it would filter to nothing.
        assertEquals(listOf(Tag("history"), Tag("spanish")), vm.state.value.topics.items)
    }

    // ── tiles ────────────────────────────────────────────────────────────

    @Test
    fun deckCardCarriesAuthorAndEmojiFallback() = runTest {
        seedGlobal()
        val vm = viewModel()

        advanceUntilIdle()

        val card = vm.state.value.browse.items.first()
        // No profile published — the tile carries the bare pubky for the UI to truncate.
        assertEquals("stranger1", card.author.pubky)
        assertNull(card.author.displayName)
        // No cover emoji set — falls back to the title's first character.
        assertEquals("D", card.coverEmoji)
    }

    @Test
    fun authorProfilesResolveIntoBothStripsAfterFirstPaint() = runTest {
        seedFeed()
        discovery.globalDecks = listOf(testDeck(id = "shared", authorPubky = "friend1"))
        identity.profiles["friend1"] = PubkyIdentity("friend1", "Ada Lovelace", avatarUrl = null, bio = null)
        val vm = viewModel()

        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("Ada Lovelace", state.browse.items.single().author.displayName)
        assertEquals(
            "Ada Lovelace",
            state.following.items.first { it.authorPubky == "friend1" }.author.displayName,
        )
        // friend2 has no profile — it keeps the pubky rather than blanking out.
        assertNull(state.following.items.first { it.authorPubky == "friend2" }.author.displayName)
    }

    // ── people ───────────────────────────────────────────────────────────

    @Test
    fun peopleAreSeededFromWhateverBrowseFound() = runTest {
        seedGlobal()
        val vm = viewModel()

        advanceUntilIdle()

        // The directory is empty here, exactly as it is against the live indexer — the strip is
        // carried entirely by the authors of the decks browse just fetched.
        assertEquals(listOf("stranger1", "stranger2"), vm.state.value.people.items.map { it.identity.pubky })
        assertEquals(listOf(DiscoverViewModel.PEOPLE_LIMIT), discovery.suggestedRequests)
    }

    @Test
    fun peopleDropsAccountsYouAlreadyFollow() = runTest {
        seedGlobal()
        discovery.follows.add("stranger1")
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(listOf("stranger2"), vm.state.value.people.items.map { it.identity.pubky })
    }

    @Test
    fun followingSomeoneFromTheStripIsOptimistic() = runTest {
        seedGlobal()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onFollowToggle("stranger1")

        // Flipped before the write comes back, so the pill responds to the tap immediately.
        assertTrue(vm.state.value.people.items.first().isFollowing)
        advanceUntilIdle()
        assertTrue(vm.state.value.people.items.first().isFollowing)
        assertFalse(vm.state.value.people.items.first().isFollowPending)
        assertTrue("stranger1" in discovery.follows)
    }

    @Test
    fun aFailedFollowRevertsThePillAndSaysWhy() = runTest {
        seedGlobal()
        discovery.followError = IllegalStateException("offline")
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<DiscoverEffect>()
        val collector = launch { vm.effects.toList(effects) }

        vm.onFollowToggle("stranger1")
        advanceUntilIdle()

        val person = vm.state.value.people.items.first()
        assertFalse(person.isFollowing, "an optimistic follow must not survive a failed write")
        assertFalse(person.isFollowPending)
        assertTrue(effects.any { it is DiscoverEffect.ShowFollowError })
        collector.cancel()
    }

    @Test
    fun refreshClearsIsRefreshingOnceEveryStripSettles() = runTest {
        seedFeed()
        seedGlobal()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onRefresh()
        advanceUntilIdle()

        assertFalse(vm.state.value.isRefreshing)
        assertNotNull(vm.state.value.browse.items.firstOrNull())
    }

    @Test
    fun browseTilesCarryTheDeckCoverImage() = runTest {
        val cover = testCoverImage()
        discovery.globalDecks = listOf(testDeck(id = "kanji", authorPubky = "stranger2", coverImageRef = cover))
        val vm = viewModel()

        advanceUntilIdle()

        // Discover's tiles used to get the emoji only, so a deck with real cover art rendered
        // as its title initial here and on the tag-browse screen that reuses the same row.
        assertEquals(cover, vm.state.value.browse.items.single().coverImage)
    }
    // ── Browsing without an account (#150) ───────────────────────────────────

    /**
     * Discover is the whole app for a signed-out visitor, so it has to work: global browse and
     * the people strip read public records and need no session. The followed strip is the one
     * thing that cannot — there is no follow graph — so it settles empty rather than spinning.
     */
    @Test
    fun `browse loads with nobody signed in and the followed strip does not`() =
        runTest(mainDispatcher) {
            identity.session = null
            seedFeed()
            seedGlobal()

            val vm = viewModel()
            advanceUntilIdle()

            val state = vm.state.value
            assertFalse(state.isSignedIn)
            assertEquals(2, state.browse.items.size)
            assertTrue(state.following.items.isEmpty())
            assertFalse(state.following.isLoading)
        }

    @Test
    fun `following a person with no account raises the prompt instead of writing`() =
        runTest(mainDispatcher) {
            identity.session = null
            seedGlobal()

            val vm = viewModel()
            val effects = mutableListOf<DiscoverEffect>()
            val job = launch { vm.effects.collect { effects.add(it) } }
            advanceUntilIdle()

            vm.onFollowToggle("stranger1")
            advanceUntilIdle()
            job.cancel()

            assertEquals(listOf<DiscoverEffect>(DiscoverEffect.RequireSignIn(SignInReason.FollowPerson)), effects)
            assertTrue(discovery.follows.isEmpty())
        }

    // ── paging (#321) ────────────────────────────────────────────────────

    private fun seedManyGlobalDecks(count: Int) {
        discovery.globalDecks = (0 until count).map {
            testDeck(id = "deck$it", authorPubky = "stranger$it", tags = listOf(Tag("chess")))
        }
    }

    @Test
    fun `browse appends the next page instead of replacing the grid`() = runTest(mainDispatcher) {
        seedManyGlobalDecks(DiscoverViewModel.BROWSE_LIMIT * 2)

        val vm = viewModel()
        advanceUntilIdle()
        val first = vm.state.value.browse.items.map { it.id }
        assertEquals(DiscoverViewModel.BROWSE_LIMIT, first.size)
        assertTrue(vm.state.value.browse.hasMore)

        vm.onBrowseEndReached()
        advanceUntilIdle()

        val all = vm.state.value.browse.items.map { it.id }
        assertEquals(DiscoverViewModel.BROWSE_LIMIT * 2, all.size)
        // The page arrived under what was already there, in order — not in place of it.
        assertEquals(first, all.take(first.size))
        assertFalse(vm.state.value.browse.hasMore)
    }

    @Test
    fun `a page load never blanks the grid it is extending`() = runTest(mainDispatcher) {
        seedManyGlobalDecks(DiscoverViewModel.BROWSE_LIMIT * 2)
        val vm = viewModel()
        advanceUntilIdle()

        discovery.globalGate = CompletableDeferred()
        vm.onBrowseEndReached()
        advanceUntilIdle()

        // isLoadingMore draws a footer; isLoading would take the whole strip away mid-scroll.
        assertTrue(vm.state.value.browse.isLoadingMore)
        assertFalse(vm.state.value.browse.isLoading)
        assertEquals(DiscoverViewModel.BROWSE_LIMIT, vm.state.value.browse.items.size)
        discovery.globalGate?.complete(Unit)
    }

    @Test
    fun `a second end-reached while one page is in flight asks for nothing`() =
        runTest(mainDispatcher) {
            seedManyGlobalDecks(DiscoverViewModel.BROWSE_LIMIT * 3)
            val vm = viewModel()
            advanceUntilIdle()
            val afterFirstPaint = discovery.globalRequests.size

            discovery.globalGate = CompletableDeferred()
            vm.onBrowseEndReached()
            advanceUntilIdle()
            // A scroll position fires this repeatedly; each extra call must cost nothing.
            vm.onBrowseEndReached()
            vm.onBrowseEndReached()
            advanceUntilIdle()

            assertEquals(afterFirstPaint + 1, discovery.globalRequests.size)
            discovery.globalGate?.complete(Unit)
        }

    @Test
    fun `end-reached does nothing once the indexer is exhausted`() = runTest(mainDispatcher) {
        seedGlobal()
        val vm = viewModel()
        advanceUntilIdle()
        val requests = discovery.globalRequests.size
        assertFalse(vm.state.value.browse.hasMore)

        vm.onBrowseEndReached()
        advanceUntilIdle()

        assertEquals(requests, discovery.globalRequests.size)
    }

    @Test
    fun `choosing a topic restarts browse at the first page`() = runTest(mainDispatcher) {
        seedManyGlobalDecks(DiscoverViewModel.BROWSE_LIMIT * 2)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onBrowseEndReached()
        advanceUntilIdle()
        assertEquals(DiscoverViewModel.BROWSE_LIMIT * 2, vm.state.value.browse.items.size)

        vm.onTagSelected(Tag("chess"))
        advanceUntilIdle()

        // A fresh question, not a continuation of the last one: the old cursor would page into
        // the middle of a different result set.
        assertEquals(DiscoverViewModel.BROWSE_LIMIT, vm.state.value.browse.items.size)
    }

    @Test
    fun `the people carousel pages too`() = runTest(mainDispatcher) {
        discovery.loopkyUsers = (0 until DiscoverViewModel.PEOPLE_LIMIT * 2).map {
            PubkyIdentity("person$it", displayName = "Person $it", avatarUrl = null, bio = null)
        }

        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(DiscoverViewModel.PEOPLE_LIMIT, vm.state.value.people.items.size)
        assertTrue(vm.state.value.people.hasMore)

        vm.onPeopleEndReached()
        advanceUntilIdle()

        val pubkys = vm.state.value.people.items.map { it.identity.pubky }
        assertEquals(DiscoverViewModel.PEOPLE_LIMIT * 2, pubkys.size)
        assertEquals(pubkys.distinct(), pubkys)
        assertFalse(vm.state.value.people.hasMore)
    }

    // ── an unreachable indexer is not an empty network (#321) ────────────

    @Test
    fun `an unreachable indexer shows an error, never the empty state`() = runTest(mainDispatcher) {
        // "Nothing published here yet" is a claim about the world. A device that never heard from
        // the indexer cannot make it, and used to make it confidently and with no way to retry.
        discovery.globalError = RuntimeException("Unable to resolve host \"nexus.test\"")

        val vm = viewModel()
        advanceUntilIdle()

        val browse = vm.state.value.browse
        assertNotNull(browse.error)
        assertFalse(browse.isEmpty, "a failed read must not read as an empty network")
        assertTrue(browse.items.isEmpty())
    }

    @Test
    fun `retrying browse asks again and clears the error`() = runTest(mainDispatcher) {
        discovery.globalError = RuntimeException("boom")
        val vm = viewModel()
        advanceUntilIdle()
        assertNotNull(vm.state.value.browse.error)

        discovery.globalError = null
        seedGlobal()
        vm.onRetryBrowse()
        advanceUntilIdle()

        assertNull(vm.state.value.browse.error)
        assertEquals(2, vm.state.value.browse.items.size)
    }

    @Test
    fun `a failed page keeps the decks already on screen`() = runTest(mainDispatcher) {
        seedManyGlobalDecks(DiscoverViewModel.BROWSE_LIMIT * 2)
        val vm = viewModel()
        advanceUntilIdle()
        val first = vm.state.value.browse.items.size

        discovery.globalError = RuntimeException("boom")
        vm.onBrowseEndReached()
        advanceUntilIdle()

        val browse = vm.state.value.browse
        // A footer that could not load, not a strip that could not load.
        assertEquals(first, browse.items.size)
        assertNotNull(browse.pageError)
        assertNull(browse.error)
        assertFalse(browse.isLoadingMore, "the footer would spin forever")
        // And it must not re-ask on every recomposition of the sentinel.
        assertFalse(browse.canLoadMore)
    }

    @Test
    fun `retrying a failed page resumes from the same cursor`() = runTest(mainDispatcher) {
        seedManyGlobalDecks(DiscoverViewModel.BROWSE_LIMIT * 2)
        val vm = viewModel()
        advanceUntilIdle()
        discovery.globalError = RuntimeException("boom")
        vm.onBrowseEndReached()
        advanceUntilIdle()

        discovery.globalError = null
        vm.onRetryBrowsePage()
        advanceUntilIdle()

        val items = vm.state.value.browse.items.map { it.id }
        assertEquals(DiscoverViewModel.BROWSE_LIMIT * 2, items.size)
        assertEquals(items.distinct(), items)
        assertNull(vm.state.value.browse.pageError)
    }
}
