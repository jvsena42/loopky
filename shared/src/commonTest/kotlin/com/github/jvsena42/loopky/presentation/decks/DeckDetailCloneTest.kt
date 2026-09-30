package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Copying someone else's deck (#33), and the route to it (#254): Follow is the only offer on a
 * deck you do not own, and Edit on one you *follow* raises the copy prompt rather than the editor.
 *
 * Split out of [DeckDetailViewModelTest], which is at detekt's class-size ceiling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckDetailCloneTest {

    private val identityRepo = FakeIdentityRepository()
    private val deckRepo = FakeDeckRepository()
    private val cardRepo = FakeCardRepository()
    private val srsRepo = FakeSrsRepository()
    private val tagRepo = RecordingTagRepository()
    private val discoveryRepo = FakeDiscoveryRepository()
    private val preferences = FakeAppPreferences()

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(deckId: String = "deck1", authorPubky: String? = null) =
        DeckDetailViewModel(
            deckId = deckId,
            authorPubky = authorPubky,
            deckRepository = deckRepo,
            cardRepository = cardRepo,
            identityRepository = identityRepo,
            srsRepository = srsRepo,
            mediaRepository = FakeMediaRepository(),
            tagRepository = tagRepo,
            discoveryRepository = discoveryRepo,
            appPreferences = preferences,
        )

    @Test
    fun `editing a deck you own opens the editor rather than offering a copy`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = TEST_PUBKY)
            val vm = viewModel()
            advanceUntilIdle()

            val effects = mutableListOf<DeckDetailEffect>()
            val job = launch { vm.effects.collect { effects.add(it) } }
            advanceUntilIdle()

            vm.onEditClick()
            advanceUntilIdle()
            job.cancel()

            assertEquals<List<DeckDetailEffect>>(
                listOf(DeckDetailEffect.NavigateEditDeck("deck1")),
                effects,
            )
            assertFalse(assertIs<DeckDetailUiState.Content>(vm.state.value).showCloneConfirm)
        }

    @Test
    fun `a blank title is refused rather than copying the deck unnamed`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()

            vm.onEditClick()
            vm.onConfirmClone("   ")
            advanceUntilIdle()

            // Still asking, and nothing spent: the copy joins a library that already holds the
            // deck it forked, so it has to be told apart from it.
            assertTrue(assertIs<DeckDetailUiState.Content>(vm.state.value).showCloneConfirm)
            assertTrue(deckRepo.cloned.isEmpty())
        }

    @Test
    fun `the source's own name is refused whatever its case and spacing`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk").copy(title = "Animals PT")
            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()

            vm.onEditClick()
            vm.onConfirmClone("  animals pt ")
            advanceUntilIdle()

            // Naming the copy is what tells it apart from the deck it forked, sitting next to it in
            // the library — so a name that only differs by case or padding defeats the rule.
            assertTrue(assertIs<DeckDetailUiState.Content>(vm.state.value).showCloneConfirm)
            assertTrue(deckRepo.cloned.isEmpty())

            vm.onConfirmClone("Animals PT, my way")
            advanceUntilIdle()
            assertEquals(listOf("Animals PT, my way"), deckRepo.cloneTitles)
        }

    @Test
    fun `cloning confirms first then navigates to the copy`() = runTest(mainDispatcher) {
        // The share offer is exercised separately below; here it would only sit between the clone
        // and its navigation.
        preferences.setShareOnPubky(false)
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk", cardCount = 40)
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val effects = mutableListOf<DeckDetailEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }
        advanceUntilIdle()

        // Edit is the entry point on a deck that is not yours — there is no Clone pill (#254).
        vm.onEditClick()
        advanceUntilIdle()
        // A clone is N+1 writes, so it asks before spending them.
        assertTrue(assertIs<DeckDetailUiState.Content>(vm.state.value).showCloneConfirm)
        assertTrue(deckRepo.cloned.isEmpty())

        vm.onConfirmClone("Animals, my way")
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("deck1"), deckRepo.cloned.map { it.id })
        assertEquals(listOf("Animals, my way"), deckRepo.cloneTitles)
        // The copy is what the user now owns; leaving them on the source looks like nothing happened.
        assertEquals<List<DeckDetailEffect>>(
            listOf(DeckDetailEffect.Cloned("clone-of-deck1")),
            effects,
        )
    }

    /**
     * The spinner used to be cleared only inside the share offer, which returns early when
     * announcements are off — so with that one setting off, a copy that had fully succeeded left
     * "Copying deck…" over the screen forever. Found by driving iOS, invisible to every test.
     */
    @Test
    fun `a copy clears its spinner even with announcements switched off`() =
        runTest(mainDispatcher) {
            preferences.setShareOnPubky(false)
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()

            vm.onEditClick()
            vm.onConfirmClone("My copy")
            advanceUntilIdle()

            assertFalse(assertIs<DeckDetailUiState.Content>(vm.state.value).isCloning)
        }

    @Test
    fun `dismissing the clone dialog spends nothing`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        vm.onEditClick()
        vm.onDismissClone()
        advanceUntilIdle()

        assertEquals(false, assertIs<DeckDetailUiState.Content>(vm.state.value).showCloneConfirm)
        assertTrue(deckRepo.cloned.isEmpty())
    }

    @Test
    fun `a failed clone reports and clears the spinner`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
        deckRepo.cloneError = IllegalStateException("homeserver unreachable")
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        vm.onEditClick()
        vm.onConfirmClone("My copy")
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(false, state.isCloning)
        assertNotNull(state.errorReason)

        vm.onDismissError()
        assertNull(assertIs<DeckDetailUiState.Content>(vm.state.value).errorReason)
    }

    @Test
    fun `a clone credits the deck it came from`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", authorPubky = TEST_PUBKY).copy(
            source = DeckSource(
                kind = DeckSource.Kind.Clone,
                uri = "pubky://friendpk/pub/loopky/decks/orig/manifest.json",
            ),
        )
        identityRepo.profiles["friendpk"] =
            PubkyIdentity("friendpk", displayName = "Mei", avatarUrl = null, bio = null)

        val vm = viewModel()
        advanceUntilIdle()

        // Attribution has to reach the screen, not just sit in the manifest's `source` block.
        assertEquals("Mei", assertIs<DeckDetailUiState.Content>(vm.state.value).clonedFrom?.displayName)
    }

    @Test
    fun `follower and clone counts come from the reserved labels`() = runTest(mainDispatcher) {
        val deck = testDeck(id = "deck1", authorPubky = "friendpk")
        deckRepo.decks["deck1"] = deck
        tagRepo.counts = mapOf(
            deck.pubkyUri to mapOf(ReservedTags.FOLLOWED to 12, ReservedTags.CLONED to 3),
        )

        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(expected = 12, actual = state.followerCount)
        assertEquals(expected = 3, actual = state.clonedCount)
    }

    @Test
    fun `an unreachable indexer leaves the counts at zero rather than failing the screen`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
            tagRepo.failWith = IllegalStateException("indexer unreachable")

            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()

            val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
            assertEquals(expected = 0, actual = state.followerCount)
            assertEquals(expected = 0, actual = state.clonedCount)
        }
}
