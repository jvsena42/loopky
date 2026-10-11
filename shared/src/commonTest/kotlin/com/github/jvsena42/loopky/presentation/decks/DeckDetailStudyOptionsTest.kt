package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.StudyFeature
import com.github.jvsena42.loopky.domain.model.StudyFeatures
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.RecordingTagRepository
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
 * Edit on a deck you follow: your own study opt-ins first, the copy one step further in.
 *
 * Split out of [DeckDetailViewModelTest], which is at detekt's class-size ceiling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckDetailStudyOptionsTest {

    private val deckRepo = FakeDeckRepository()
    private val mainDispatcher = StandardTestDispatcher()

    // The fixture's author opt-ins: listen and speak on, type and reverse off.
    private val authored = StudyFeatures(listen = true, speak = true, type = false, reverse = false)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        deckRepo.followedDecks["deck1"] = testDeck(authorPubky = "friendpk", frontLang = "en-US", backLang = "es-ES")
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = DeckDetailViewModel(
        deckId = "deck1",
        authorPubky = "friendpk",
        deckRepository = deckRepo,
        cardRepository = FakeCardRepository(),
        identityRepository = FakeIdentityRepository(),
        srsRepository = FakeSrsRepository(),
        mediaRepository = FakeMediaRepository(),
        tagRepository = RecordingTagRepository(),
        discoveryRepository = FakeDiscoveryRepository(),
        appPreferences = FakeAppPreferences(),
    )

    private fun DeckDetailViewModel.content() = assertIs<DeckDetailUiState.Content>(state.value)

    @Test
    fun `editing a followed deck offers your study options before any copy`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.onEditClick()
            advanceUntilIdle()

            val options = assertNotNull(vm.content().studyOptions)
            assertEquals(authored, options.chosen)
            assertEquals(authored, options.author)
            assertFalse(options.differsFromAuthor)
            assertFalse(vm.content().showCloneConfirm)
        }

    @Test
    fun `saving writes the reader's opt-ins and the screen shows them`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onEditClick()
            advanceUntilIdle()

            vm.onStudyOptionChange(StudyFeature.Listen, false)
            vm.onStudyOptionChange(StudyFeature.Typing, true)
            vm.onStudyOptionChange(StudyFeature.Reverse, true)
            vm.onSaveStudyOptions()
            advanceUntilIdle()

            val mine = StudyFeatures(listen = false, speak = true, type = true, reverse = true)
            assertEquals(listOf("deck1" to mine), deckRepo.customizedStudy)
            val content = vm.content()
            assertNull(content.studyOptions)
            assertFalse(content.listenEnabled)
            assertTrue(content.speakEnabled)
            assertTrue(content.typeEnabled)
            assertTrue(content.reverseEnabled)
            assertTrue(content.isStudyCustomized)
            // Nothing was copied to get here.
            assertTrue(deckRepo.cloned.isEmpty())
        }

    @Test
    fun `reopening the sheet shows what was saved against what the author chose`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onEditClick()
            advanceUntilIdle()
            vm.onStudyOptionChange(StudyFeature.Typing, true)
            vm.onSaveStudyOptions()
            advanceUntilIdle()

            vm.onEditClick()
            advanceUntilIdle()

            val options = assertNotNull(vm.content().studyOptions)
            assertEquals(authored.copy(type = true), options.chosen)
            assertEquals(authored, options.author)
        }

    @Test
    fun `resetting returns to the author's opt-ins and saving clears the mark`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onEditClick()
            advanceUntilIdle()
            vm.onStudyOptionChange(StudyFeature.Typing, true)
            vm.onSaveStudyOptions()
            advanceUntilIdle()

            vm.onEditClick()
            advanceUntilIdle()
            vm.onResetStudyOptions()
            vm.onSaveStudyOptions()
            advanceUntilIdle()

            assertEquals(authored, deckRepo.customizedStudy.last().second)
            assertFalse(vm.content().isStudyCustomized)
            assertFalse(vm.content().typeEnabled)
        }

    @Test
    fun `dismissing the sheet writes nothing`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onEditClick()
            advanceUntilIdle()

            vm.onStudyOptionChange(StudyFeature.Typing, true)
            vm.onDismissStudyOptions()
            advanceUntilIdle()

            assertTrue(deckRepo.customizedStudy.isEmpty())
            assertNull(vm.content().studyOptions)
            assertFalse(vm.content().typeEnabled)
        }

    @Test
    fun `the copy is the second step and discards the unsaved draft`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onEditClick()
            advanceUntilIdle()
            vm.onStudyOptionChange(StudyFeature.Typing, true)

            vm.onCopyFromStudyOptions()
            advanceUntilIdle()

            assertNull(vm.content().studyOptions)
            assertTrue(vm.content().showCloneConfirm)
            assertTrue(deckRepo.customizedStudy.isEmpty())
            assertTrue(deckRepo.cloned.isEmpty())
        }

    @Test
    fun `a failed save reports the error and leaves the opt-ins as they were`() =
        runTest(mainDispatcher) {
            deckRepo.customizeStudyError = IllegalStateException("boom")
            val vm = viewModel()
            advanceUntilIdle()
            vm.onEditClick()
            advanceUntilIdle()

            vm.onStudyOptionChange(StudyFeature.Typing, true)
            vm.onSaveStudyOptions()
            advanceUntilIdle()

            val content = vm.content()
            assertIs<ErrorReason>(content.errorReason)
            assertNull(content.studyOptions)
            assertFalse(content.typeEnabled)
            assertFalse(content.isStudyCustomized)
        }

    @Test
    fun `listen and speak on a deck with no language pair stay inert whatever is chosen`() =
        runTest(mainDispatcher) {
            deckRepo.followedDecks["deck1"] = testDeck(authorPubky = "friendpk")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onEditClick()
            advanceUntilIdle()

            assertFalse(assertNotNull(vm.content().studyOptions).speechReady)
            vm.onSaveStudyOptions()
            advanceUntilIdle()
            assertFalse(vm.content().listenEnabled)
            assertFalse(vm.content().speakEnabled)
        }

    @Test
    fun `unfollowing drops the reader's opt-ins from the screen`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onEditClick()
            advanceUntilIdle()
            vm.onStudyOptionChange(StudyFeature.Typing, true)
            vm.onSaveStudyOptions()
            advanceUntilIdle()

            vm.onToggleFollow()
            advanceUntilIdle()

            val content = vm.content()
            assertFalse(content.isFollowing)
            assertFalse(content.isStudyCustomized)
            assertFalse(content.typeEnabled)
        }

    /**
     * Found on a device: copying unfollows, the unfollow announces a change, and the reload it
     * triggered rebuilt the screen's state from scratch — taking the share prompt with it, and with
     * the prompt the only thing that would ever navigate to the copy.
     */
    @Test
    fun `copying a followed deck still offers to announce it and then opens the copy`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            val effects = mutableListOf<DeckDetailEffect>()
            val job = launch { vm.effects.collect { effects.add(it) } }
            vm.onEditClick()
            advanceUntilIdle()
            vm.onCopyFromStudyOptions()

            vm.onConfirmClone("My copy")
            advanceUntilIdle()

            assertNotNull(vm.content().sharePrompt)
            vm.onShareDismiss()
            advanceUntilIdle()
            job.cancel()
            assertEquals<List<DeckDetailEffect>>(listOf(DeckDetailEffect.Cloned("clone-of-deck1")), effects)
        }
}
