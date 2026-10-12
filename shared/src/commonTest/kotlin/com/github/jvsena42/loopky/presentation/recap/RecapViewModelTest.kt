package com.github.jvsena42.loopky.presentation.recap

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.domain.model.RecapShareTarget
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeSettingsRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testDeck
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecapViewModelTest {
    private val identity = FakeIdentityRepository()
    private val decks = FakeDeckRepository()
    private val srs = FakeSrsRepository()
    private val settings = FakeSettingsRepository()
    private val discovery = FakeDiscoveryRepository()
    private val mainDispatcher = StandardTestDispatcher()

    private val midYear = 1_767_225_600_000L + 150 * DAY
    private val url = "https://loopky.app/recap/?pubky=$TEST_PUBKY&year=2026"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = RecapViewModel(
        year = 2026,
        identityRepository = identity,
        deckRepository = decks,
        srsRepository = srs,
        settingsRepository = settings,
        discoveryRepository = discovery,
    )

    private fun TestScope.collectEffects(vm: RecapViewModel): List<RecapEffect> {
        val effects = mutableListOf<RecapEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects.add(it) } }
        return effects
    }

    private suspend fun seedStudiedDeck(cards: Int = 3) {
        decks.decks["d1"] = testDeck(id = "d1", title = "Spanish")
        repeat(cards) { index ->
            srs.upsert(
                "d1",
                SrsState(
                    cardId = "c$index",
                    dueAt = midYear + 7 * DAY,
                    intervalDays = 7,
                    easeFactor = 2.5,
                    repetitions = 1,
                    lastGrade = SrsGrade.Easy,
                ),
            )
        }
    }

    @Test
    fun `a studied year loads as content with the link to its web page`() = runTest {
        seedStudiedDeck()
        val vm = viewModel()
        assertIs<RecapUiState.Loading>(vm.state.value)

        advanceUntilIdle()

        val state = assertIs<RecapUiState.Content>(vm.state.value)
        assertEquals(3, state.recap.cardsStudied)
        assertEquals(3, state.recap.cardsMastered)
        assertEquals("Spanish", state.recap.topDeck?.title)
        assertEquals(url, state.webUrl)
    }

    @Test
    fun `a year with nothing in it is empty rather than a page of zeroes`() = runTest {
        decks.decks["d1"] = testDeck(id = "d1", createdAt = 1_000L)
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(RecapUiState.Empty(2026), vm.state.value)
    }

    @Test
    fun `followed decks failing to list still leaves the user their own year`() = runTest {
        seedStudiedDeck()
        decks.listFollowedError = PubkyError("down")
        val vm = viewModel()

        advanceUntilIdle()

        assertIs<RecapUiState.Content>(vm.state.value)
    }

    @Test
    fun `own decks failing to list is an error that retry recovers from`() = runTest {
        seedStudiedDeck()
        decks.listOwnedError = PubkyError("down")
        val vm = viewModel()
        advanceUntilIdle()
        assertIs<RecapUiState.Error>(vm.state.value)

        decks.listOwnedError = null
        vm.onRetry()
        advanceUntilIdle()

        assertIs<RecapUiState.Content>(vm.state.value)
    }

    @Test
    fun `sharing to an app hands over the caption with the link appended`() = runTest {
        seedStudiedDeck()
        val vm = viewModel()
        val effects = collectEffects(vm)
        advanceUntilIdle()

        vm.onShare(RecapShareTarget.WhatsApp, "My year")
        advanceUntilIdle()

        val share = assertIs<RecapEffect.Share>(effects.single())
        assertEquals(RecapShareTarget.WhatsApp, share.target)
        assertEquals("My year\n\n$url", share.text)
        assertTrue(discovery.recapPosts.isEmpty())
    }

    @Test
    fun `pubky shows the post first and writes exactly what it showed`() = runTest {
        seedStudiedDeck()
        val vm = viewModel()
        val effects = collectEffects(vm)
        advanceUntilIdle()

        vm.onShare(RecapShareTarget.Pubky, "My year")
        val preview = assertIs<RecapUiState.Content>(vm.state.value).pubkyPrompt?.preview
        assertTrue(discovery.recapPosts.isEmpty())
        vm.onPostConfirm()
        advanceUntilIdle()

        assertEquals(listOf("My year\n\n$url" to url), discovery.recapPosts)
        assertEquals("My year\n\n$url", preview)
        assertEquals(listOf<RecapEffect>(RecapEffect.Posted), effects)
        assertNull(assertIs<RecapUiState.Content>(vm.state.value).pubkyPrompt)
    }

    @Test
    fun `declining the pubky post writes nothing`() = runTest {
        seedStudiedDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onShare(RecapShareTarget.Pubky, "My year")
        vm.onPostDismiss()
        advanceUntilIdle()

        assertTrue(discovery.recapPosts.isEmpty())
        assertNull(assertIs<RecapUiState.Content>(vm.state.value).pubkyPrompt)
    }

    @Test
    fun `a failed post is reported and the recap stays on screen`() = runTest {
        seedStudiedDeck()
        discovery.recapPostError = PubkyError("down")
        val vm = viewModel()
        val effects = collectEffects(vm)
        advanceUntilIdle()

        vm.onShare(RecapShareTarget.Pubky, "My year")
        vm.onPostConfirm()
        advanceUntilIdle()

        assertIs<RecapEffect.PostFailed>(effects.single())
        val state = assertIs<RecapUiState.Content>(vm.state.value)
        assertNull(state.pubkyPrompt)
        assertEquals(3, state.recap.cardsStudied)
    }

    @Test
    fun `a second confirm while the post is in flight does not post twice`() = runTest {
        seedStudiedDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onShare(RecapShareTarget.Pubky, "My year")
        vm.onPostConfirm()
        vm.onPostConfirm()
        advanceUntilIdle()

        assertEquals(1, discovery.recapPosts.size)
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
