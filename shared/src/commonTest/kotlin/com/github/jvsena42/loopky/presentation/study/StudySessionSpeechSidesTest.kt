package com.github.jvsena42.loopky.presentation.study

import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeSettingsRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Which face Listen and Speak sit on: the answer only, unless the deck asked for both sides (#595). */
@OptIn(ExperimentalCoroutinesApi::class)
class StudySessionSpeechSidesTest {

    private val srsRepo = FakeSrsRepository()
    private val deckRepo = FakeDeckRepository()
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = StudySessionViewModel(
        deckId = "deck1",
        srsRepository = srsRepo,
        deckRepository = deckRepo,
        cardRepository = FakeCardRepository(),
        settingsRepository = FakeSettingsRepository(),
        identityRepository = FakeIdentityRepository(),
        discoveryRepository = FakeDiscoveryRepository(),
        appPreferences = FakeAppPreferences(),
    )

    private fun seedSpeechDeck(bothSides: Boolean) {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            title = "Spanish",
            frontLang = "es-ES",
            backLang = "en-US",
            speechBothSides = bothSides,
        )
        srsRepo.due = listOf(
            testCard("c1", front = "hola", back = "hello"),
            testCard("c2", front = "gracias", back = "thank you"),
        )
    }

    @Test
    fun listenAndSpeakWaitForTheFlipByDefault() = runTest {
        // On the prompt they would read or grade the question before the reader tried to recall
        // the answer, and Speak would transcribe the answer with the prompt's language model.
        seedSpeechDeck(bothSides = false)
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }

        val front = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertTrue(front.listenEnabled && front.speakEnabled, "the deck has both opt-ins on")
        assertFalse(front.showsListen)
        assertFalse(front.showsSpeak)
        vm.onSpeak()
        vm.onSpeakTest()
        advanceUntilIdle()
        assertEquals(emptyList(), effects.excludingHaptics())
        assertEquals(SpeakPhase.Idle, assertIs<StudySessionUiState.Reviewing>(vm.state.value).speakPhase)

        vm.onReveal()
        advanceUntilIdle()
        val back = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertTrue(back.showsListen)
        assertTrue(back.showsSpeak)
        vm.onSpeak()
        advanceUntilIdle()
        assertEquals(StudySessionEffect.Speak("hello", "en-US"), effects.excludingHaptics().single())
        vm.onListenFinished()
        vm.onSpeakTest()
        advanceUntilIdle()
        assertEquals(
            StudySessionEffect.StartSpeechRecognition("hello", "en-US"),
            effects.excludingHaptics().last(),
        )
        job.cancel()
    }

    @Test
    fun bothSidesPutsListenAndSpeakOnThePromptToo() = runTest {
        seedSpeechDeck(bothSides = true)
        val vm = viewModel()
        advanceUntilIdle()

        val front = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertTrue(front.showsListen)
        assertTrue(front.showsSpeak)
    }
}
