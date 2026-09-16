package com.github.jvsena42.loopky.presentation.discover

import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.testDeck
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TagBrowseViewModelTest {

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

    private fun viewModel(tag: String = "spanish") = TagBrowseViewModel(
        tag = Tag(tag),
        discoveryRepository = discovery,
        identityRepository = identity,
    )

    @Test
    fun showsEveryDeckOnTheNetworkCarryingTheTag() = runTest {
        discovery.globalDecksByTag = mapOf(
            Tag("spanish") to listOf(
                testDeck(id = "d1", authorPubky = "stranger1", tags = listOf(Tag("spanish"))),
                testDeck(id = "d2", authorPubky = "stranger2", tags = listOf(Tag("spanish"))),
            ),
        )
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<TagBrowseUiState.Content>(vm.state.value)
        assertEquals(listOf("d1", "d2"), state.decks.map { it.id })
    }

    @Test
    fun asksTheIndexerForTheWholeScreenNotOneStrip() = runTest {
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(listOf(Tag("spanish") to TagBrowseViewModel.BROWSE_LIMIT), discovery.globalRequests)
        // A dedicated screen takes the repository default rather than Discover's tighter budget.
        assertEquals(30, TagBrowseViewModel.BROWSE_LIMIT)
        assertIs<TagBrowseUiState.Empty>(vm.state.value)
    }

    @Test
    fun nothingTaggedShowsTheEmptyState() = runTest {
        discovery.globalDecksByTag = mapOf(Tag("other") to listOf(testDeck(id = "d1")))
        val vm = viewModel(tag = "spanish")

        advanceUntilIdle()

        assertIs<TagBrowseUiState.Empty>(vm.state.value)
    }

    @Test
    fun authorProfilesResolveAfterFirstPaint() = runTest {
        discovery.globalDecksByTag = mapOf(
            Tag("spanish") to listOf(
                testDeck(id = "d1", authorPubky = "stranger1", tags = listOf(Tag("spanish"))),
                testDeck(id = "d2", authorPubky = "stranger2", tags = listOf(Tag("spanish"))),
            ),
        )
        identity.profiles["stranger1"] = PubkyIdentity("stranger1", "Ada Lovelace", null, null)
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<TagBrowseUiState.Content>(vm.state.value)
        assertEquals("Ada Lovelace", state.decks.first { it.authorPubky == "stranger1" }.author.displayName)
        // No profile published — the tile keeps the pubky rather than blanking out.
        assertNull(state.decks.first { it.authorPubky == "stranger2" }.author.displayName)
    }

    @Test
    fun `an unreachable indexer is an error, not an empty tag`() = runTest(mainDispatcher) {
        // "No decks tagged X yet" is a claim about the network, and a device that never reached
        // the indexer has heard nothing about it (#321).
        discovery.globalError = RuntimeException("Unable to resolve host \"nexus.test\"")

        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value is TagBrowseUiState.Error, "was ${vm.state.value}")
    }

    @Test
    fun `retry reloads after the indexer comes back`() = runTest(mainDispatcher) {
        discovery.globalError = RuntimeException("boom")
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value is TagBrowseUiState.Error)

        discovery.globalError = null
        discovery.globalDecks = listOf(testDeck(id = "d1", tags = listOf(Tag("spanish"))))
        vm.onRetry()
        advanceUntilIdle()

        assertTrue(vm.state.value is TagBrowseUiState.Content, "was ${vm.state.value}")
    }
}
