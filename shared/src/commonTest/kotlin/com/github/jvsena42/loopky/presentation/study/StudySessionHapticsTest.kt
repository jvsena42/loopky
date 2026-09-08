package com.github.jvsena42.loopky.presentation.study

import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.platform.SpeechError
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The study loop's haptics. They are emitted here rather than fired on tap by the screens because
 * only the ViewModel knows whether a tap did anything — which is what most of these assert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudySessionHapticsTest {

    private val srsRepo = FakeSrsRepository()
    private val deckRepo = FakeDeckRepository()
    private val settingsRepo = FakeSettingsRepository()
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
        settingsRepository = settingsRepo,
        identityRepository = FakeIdentityRepository(),
    )

    private fun seedDeck(cards: Int = 2) {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish")
        srsRepo.due = List(cards) { testCard("c$it", front = "hola", back = "hello") }
    }

    @Test
    fun flippingACardTicksOnce() = runTest {
        seedDeck()
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }
        // The collector has to actually be subscribed: a haptic is `tryEmit`ed and, with nobody
        // listening, dropped rather than buffered.
        runCurrent()

        vm.onReveal()
        // The second tap lands on an already-flipped card and changes nothing; buzzing for it
        // would tell the reader something happened.
        vm.onReveal()
        advanceUntilIdle()

        assertEquals(listOf(StudyHaptic.Tick), effects.haptics())
        job.cancel()
    }

    @Test
    fun gradingTicksOnceAndTheLastCardEndsOnTheSessionsOwnSuccess() = runTest {
        seedDeck(cards = 2)
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }
        // The collector has to actually be subscribed: a haptic is `tryEmit`ed and, with nobody
        // listening, dropped rather than buffered.
        runCurrent()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        assertEquals(listOf(StudyHaptic.Tick), effects.haptics())

        // The final grade's own tick is left out: it and the completion would land a few
        // milliseconds apart and read as one smeared buzz rather than two events.
        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        assertEquals(listOf(StudyHaptic.Tick, StudyHaptic.Success), effects.haptics())
        job.cancel()
    }

    @Test
    fun theDailyGoalGetsItsOwnPatternAndNotTheOneAnyCorrectAnswerGets() = runTest(mainDispatcher) {
        settingsRepo.setStudySettings(StudySettings(newCardsPerDayGoal = 1))
        // More cards than the goal, so the celebration has a card to render over.
        seedDeck(cards = 3)
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }
        // The collector has to actually be subscribed: a haptic is `tryEmit`ed and, with nobody
        // listening, dropped rather than buffered.
        runCurrent()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()

        assertEquals(listOf(StudyHaptic.Tick, StudyHaptic.Celebration), effects.haptics())
        job.cancel()
    }

    @Test
    fun aGradeArrivingWhileTheLastOneIsStillWritingIsSilent() = runTest {
        seedDeck(cards = 3)
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }
        // The collector has to actually be subscribed: a haptic is `tryEmit`ed and, with nobody
        // listening, dropped rather than buffered.
        runCurrent()

        vm.onGrade(SrsGrade.Good)
        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()

        assertEquals(listOf(StudyHaptic.Tick), effects.haptics())
        job.cancel()
    }

    @Test
    fun aCheckedAnswerSucceedsOrWarnsAndGivingUpJustTicks() = runTest {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", typeEnabled = true)
        srsRepo.due = listOf(testCard("c1", front = "hola", back = "hello"))
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }
        // The collector has to actually be subscribed: a haptic is `tryEmit`ed and, with nobody
        // listening, dropped rather than buffered.
        runCurrent()

        vm.onAnswerChange("helo")
        vm.onCheckAnswer()
        advanceUntilIdle()
        assertEquals(listOf(StudyHaptic.Warning), effects.haptics())

        vm.onAnswerChange("hello")
        vm.onCheckAnswer()
        advanceUntilIdle()
        assertEquals(listOf(StudyHaptic.Warning, StudyHaptic.Success), effects.haptics())
        job.cancel()
    }

    @Test
    fun givingUpTicksRatherThanSayingTheAnswerWasWrong() = runTest {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", typeEnabled = true)
        srsRepo.due = listOf(testCard("c1", front = "hola", back = "hello"))
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }
        // The collector has to actually be subscribed: a haptic is `tryEmit`ed and, with nobody
        // listening, dropped rather than buffered.
        runCurrent()

        vm.onGiveUp()
        advanceUntilIdle()

        assertEquals(listOf(StudyHaptic.Tick), effects.haptics())
        job.cancel()
    }

    @Test
    fun aSpokenAttemptSucceedsOrWarnsAndAFailedListenIsNeither() = runTest {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", frontLang = "es-ES", backLang = "en-US")
        srsRepo.due = listOf(testCard("c1", front = "hola", back = "hello"))
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }
        // The collector has to actually be subscribed: a haptic is `tryEmit`ed and, with nobody
        // listening, dropped rather than buffered.
        runCurrent()

        // The tick is the only cue that the microphone opened — the sheet is the same sheet.
        vm.onSpeakTest()
        advanceUntilIdle()
        assertEquals(listOf(StudyHaptic.Tick), effects.haptics())

        vm.onSpeechResult("adios")
        advanceUntilIdle()
        assertEquals(listOf(StudyHaptic.Tick, StudyHaptic.Warning), effects.haptics())

        // A listen that produced no answer is the app failing, not the reader — `.error` on iOS
        // against the mispronunciation's `.warning`.
        vm.onSpeakRetry()
        vm.onSpeechError(SpeechError.NoMatch)
        advanceUntilIdle()
        assertEquals(
            listOf(StudyHaptic.Tick, StudyHaptic.Warning, StudyHaptic.Tick, StudyHaptic.Failure),
            effects.haptics(),
        )
        job.cancel()
    }

    @Test
    fun aLateSpeechErrorAfterTheSheetIsGoneIsSilent() = runTest {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", frontLang = "es-ES", backLang = "en-US")
        srsRepo.due = listOf(testCard("c1", front = "hola", back = "hello"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onSpeakTest()
        vm.onSpeakDismiss()
        advanceUntilIdle()

        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }
        // The collector has to actually be subscribed: a haptic is `tryEmit`ed and, with nobody
        // listening, dropped rather than buffered.
        runCurrent()

        vm.onSpeechError(SpeechError.NoMatch)
        advanceUntilIdle()

        assertEquals(emptyList(), effects.haptics())
        job.cancel()
    }
}
