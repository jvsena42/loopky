package com.github.jvsena42.loopky.presentation.discover

import com.github.jvsena42.loopky.data.repository.DeckPage
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Several topics on Discover: an AND filter over browse and the followed strip, and the topic row
 * narrowed to what the selection can still match.
 *
 * Split from `DiscoverViewModelTest` for size, not for subject — it builds the same ViewModel over
 * the same fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverMultiTagTest {

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

    private fun seedLanguageDecks() {
        discovery.globalDecks = listOf(
            testDeck(id = "ptlang", authorPubky = "s1", tags = listOf(Tag("language"), Tag("portuguese"))),
            testDeck(id = "eslang", authorPubky = "s2", tags = listOf(Tag("language"), Tag("spanish"))),
            testDeck(id = "ptstem", authorPubky = "s3", tags = listOf(Tag("portuguese"), Tag("stem"))),
        )
    }

    @Test
    fun `a chosen topic narrows the topic row to tags its decks also carry`() = runTest(mainDispatcher) {
        seedLanguageDecks()
        tagRepo.deckTags = listOf(Tag("stem"), Tag("spanish"), Tag("language"), Tag("portuguese"))
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(4, vm.state.value.visibleTopics.size)

        vm.onTagSelected(Tag("language"))
        advanceUntilIdle()

        // "stem" only sits on a deck without "language", so offering it would lead nowhere.
        assertEquals(listOf(Tag("language"), Tag("spanish"), Tag("portuguese")), vm.state.value.visibleTopics)
    }

    @Test
    fun `every chip in a narrowed row leads to at least one deck`() = runTest(mainDispatcher) {
        seedLanguageDecks()
        tagRepo.deckTags = listOf(Tag("stem"), Tag("spanish"), Tag("language"), Tag("portuguese"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTagSelected(Tag("portuguese"))
        advanceUntilIdle()

        vm.state.value.visibleTopics.filterNot { it in vm.state.value.selectedTags }.forEach { chip ->
            vm.onTagSelected(chip)
            advanceUntilIdle()
            assertTrue(vm.state.value.browse.items.isNotEmpty(), "portuguese + ${chip.value} found nothing")
            vm.onTagSelected(chip)
            advanceUntilIdle()
        }
    }

    @Test
    fun `the topic row does not collapse while a narrower browse loads`() = runTest(mainDispatcher) {
        seedLanguageDecks()
        tagRepo.deckTags = listOf(Tag("stem"), Tag("spanish"), Tag("language"), Tag("portuguese"))
        val vm = viewModel()
        advanceUntilIdle()

        discovery.globalGate = CompletableDeferred()
        vm.onTagSelected(Tag("language"))
        advanceUntilIdle()

        assertTrue(vm.state.value.browse.isLoading)
        assertEquals(listOf(Tag("language"), Tag("spanish"), Tag("portuguese")), vm.state.value.visibleTopics)
        discovery.globalGate?.complete(Unit)
    }

    @Test
    fun `dropping a topic widens the row again`() = runTest(mainDispatcher) {
        seedLanguageDecks()
        tagRepo.deckTags = listOf(Tag("stem"), Tag("spanish"), Tag("language"), Tag("portuguese"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTagSelected(Tag("language"))
        vm.onTagSelected(Tag("portuguese"))
        advanceUntilIdle()

        vm.onTagSelected(Tag("language"))
        advanceUntilIdle()

        assertTrue(Tag("language") in vm.state.value.visibleTopics)
        assertTrue(Tag("stem") in vm.state.value.visibleTopics)
    }

    @Test
    fun `a second topic narrows browse to decks carrying both`() = runTest(mainDispatcher) {
        seedLanguageDecks()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTagSelected(Tag("language"))
        vm.onTagSelected(Tag("portuguese"))
        advanceUntilIdle()

        assertEquals(listOf(Tag("language"), Tag("portuguese")), vm.state.value.selectedTags)
        assertEquals(listOf("ptlang"), vm.state.value.browse.items.map { it.id })
        // The indexer is asked for the last tag chosen; the rest are checked against the manifests.
        assertEquals(Tag("portuguese"), discovery.globalRequests.last().first)
        assertEquals(setOf(Tag("language")), discovery.globalAlsoTagged.last())
    }

    @Test
    fun `tapping a chosen topic drops only that topic`() = runTest(mainDispatcher) {
        seedLanguageDecks()
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTagSelected(Tag("language"))
        vm.onTagSelected(Tag("portuguese"))
        advanceUntilIdle()

        vm.onTagSelected(Tag("language"))
        advanceUntilIdle()

        assertEquals(listOf(Tag("portuguese")), vm.state.value.selectedTags)
        assertEquals(listOf("ptlang", "ptstem"), vm.state.value.browse.items.map { it.id })
        assertEquals(emptySet(), discovery.globalAlsoTagged.last())
    }

    @Test
    fun `clearing drops every chosen topic at once`() = runTest(mainDispatcher) {
        seedLanguageDecks()
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTagSelected(Tag("language"))
        vm.onTagSelected(Tag("portuguese"))
        advanceUntilIdle()

        vm.onTagSelected(null)
        advanceUntilIdle()

        assertEquals(emptyList(), vm.state.value.selectedTags)
        assertEquals(ReservedTags.DECK, discovery.globalRequests.last().first)
        assertEquals(3, vm.state.value.browse.items.size)
    }

    @Test
    fun `several topics narrow the followed strip to decks carrying all of them`() = runTest(mainDispatcher) {
        discovery.feed = listOf(
            testDeck(id = "both", authorPubky = "friend1", tags = listOf(Tag("spanish"), Tag("verbs"))),
            testDeck(id = "one", authorPubky = "friend2", tags = listOf(Tag("spanish"))),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTagSelected(Tag("spanish"))
        vm.onTagSelected(Tag("verbs"))
        advanceUntilIdle()

        assertEquals(listOf("both"), vm.state.value.following.items.map { it.id })
    }

    @Test
    fun `a filtered page is paged from the same tag index as the first`() = runTest(mainDispatcher) {
        // The cursor indexes one tag's index. Asking for page two under a different primary tag
        // would resume at an offset into a list it was never taken from.
        discovery.globalDecks = (0 until DiscoverViewModel.BROWSE_LIMIT * 2).map {
            testDeck(id = "deck$it", authorPubky = "s$it", tags = listOf(Tag("chess"), Tag("openings")))
        }
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTagSelected(Tag("chess"))
        vm.onTagSelected(Tag("openings"))
        advanceUntilIdle()

        vm.onBrowseEndReached()
        advanceUntilIdle()

        assertEquals(DiscoverViewModel.BROWSE_LIMIT * 2, vm.state.value.browse.items.map { it.id }.distinct().size)
        assertEquals(listOf(Tag("openings"), Tag("openings")), discovery.globalRequests.takeLast(2).map { it.first })
    }

    @Test
    fun `an empty filtered page with more behind it is followed straight on`() = runTest(mainDispatcher) {
        // Neither platform's footer asks again while it stays on screen, so an empty page with
        // hasMore would otherwise strand the grid on "nothing here" with matches still unread.
        val match = testDeck(id = "match", authorPubky = "s1", tags = listOf(Tag("chess"), Tag("openings")))
        discovery.globalPageOverride = { _, cursor, alsoTagged ->
            when {
                alsoTagged.isEmpty() -> DeckPage(emptyList(), cursor, hasMore = false)
                cursor == 0 -> DeckPage(emptyList(), nextCursor = 12, hasMore = true)
                else -> DeckPage(listOf(match), nextCursor = cursor + 12, hasMore = false)
            }
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTagSelected(Tag("chess"))
        vm.onTagSelected(Tag("openings"))
        advanceUntilIdle()

        assertEquals(listOf("match"), vm.state.value.browse.items.map { it.id })
    }

    @Test
    fun `a filter nothing matches stops reading after a bounded number of pages`() = runTest(mainDispatcher) {
        discovery.globalPageOverride = { _, cursor, _ -> DeckPage(emptyList(), cursor + 12, hasMore = true) }
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTagSelected(Tag("chess"))
        advanceUntilIdle()
        val before = discovery.globalRequests.size

        vm.onTagSelected(Tag("openings"))
        advanceUntilIdle()

        assertEquals(1 + DiscoverViewModel.MAX_EMPTY_FILTERED_PAGES, discovery.globalRequests.size - before)
        assertTrue(vm.state.value.browse.hasMore)
    }

    @Test
    fun `an empty unfiltered page is not followed on`() = runTest(mainDispatcher) {
        // Only a filter warrants the extra reads; one tag keeps exactly one read per page.
        discovery.globalPageOverride = { _, cursor, _ -> DeckPage(emptyList(), cursor + 12, hasMore = true) }
        val vm = viewModel()
        advanceUntilIdle()
        val before = discovery.globalRequests.size

        vm.onTagSelected(Tag("chess"))
        advanceUntilIdle()

        assertEquals(1, discovery.globalRequests.size - before)
    }
}
