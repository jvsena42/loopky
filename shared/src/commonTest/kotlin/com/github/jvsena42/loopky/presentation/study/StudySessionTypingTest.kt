package com.github.jvsena42.loopky.presentation.study

import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.TypedAnswerOutcome
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * "Type the answer" (#115), split out of [StudySessionViewModelTest] to keep either class a
 * readable size. Same fakes, same dispatcher setup — only the mode under test differs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudySessionTypingTest {

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

    private fun viewModel(deckId: String? = "deck1") = StudySessionViewModel(
        deckId = deckId,
        srsRepository = srsRepo,
        deckRepository = deckRepo,
        cardRepository = FakeCardRepository(),
        settingsRepository = settingsRepo,
        identityRepository = FakeIdentityRepository(),
    )

    private suspend fun seedDeck() {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish")
        srsRepo.due = listOf(
            testCard("c1", front = "hola", back = "hello"),
            testCard("c2", front = "gracias", back = "thank you"),
        )
    }

    /** A deck that has opted into typing but declared no language pair — the common import case. */
    private suspend fun seedTypingDeck() {
        seedDeck()
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish", typeEnabled = true)
    }

    @Test
    fun aTypingCardStartsWithItsAnswerMaskedAndNoGradesOnOffer() = runTest {
        seedTypingDeck()
        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertIs<TypePhase.Answering>(state.typePhase)
        assertTrue(state.answerHidden)
        assertFalse(state.gradesAvailable, "gradeable without having seen the answer")
        // Typing needs no declared pair: this deck has none and the mode is on regardless.
        assertFalse(state.listenEnabled)
    }

    @Test
    fun flippingATypingCardIsAllowedAndKeepsTheWordHidden() = runTest {
        // The flip is never blocked — what typing withholds is the word, not the gesture.
        seedTypingDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onReveal()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertTrue(state.revealed, "the flip was blocked")
        assertTrue(state.answerHidden, "the answer was handed over by the flip")
        assertFalse(state.gradesAvailable)
    }

    @Test
    fun listenReadsThePromptWhileTheAnswerIsStillMasked() = runTest {
        // A flipped-but-masked card is the one place "the side facing the user" and "the side the
        // user can read" disagree. Reading the back aloud there would speak the hidden answer.
        seedDeck()
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            title = "Spanish",
            frontLang = "es-ES",
            backLang = "en-US",
            typeEnabled = true,
        )
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onReveal()
        vm.onSpeak()
        advanceUntilIdle()
        assertEquals(StudySessionEffect.Speak("hola", "es-ES"), effects.excludingHaptics().single())

        // A correct check is what unmasks the back, and only then does Listen follow it there.
        vm.onAnswerChange("hello")
        vm.onCheckAnswer()
        vm.onSpeak()
        advanceUntilIdle()
        assertEquals(StudySessionEffect.Speak("hello", "en-US"), effects.excludingHaptics().last())
        job.cancel()
    }

    @Test
    fun aCorrectAnswerIsTheOneCheckThatOpensTheCard() = runTest {
        seedTypingDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAnswerChange("hello")
        advanceUntilIdle()
        assertEquals("hello", assertIs<StudySessionUiState.Reviewing>(vm.state.value).typedAnswer)

        vm.onCheckAnswer()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(TypePhase.Correct("hello"), state.typePhase)
        assertTrue(state.revealed)
        assertFalse(state.answerHidden)
        assertTrue(state.gradesAvailable)
        // Checking is not grading: nothing has been scheduled.
        assertTrue(srsRepo.reviews.isEmpty())
    }

    @Test
    fun aWrongAnswerSaysSoAndKeepsTheAnswerHidden() = runTest {
        // Handing over the answer the moment you slip turns one typo into a lost card. The way
        // out of a card you genuinely cannot answer is Give up, which is always right there.
        seedTypingDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAnswerChange("goodbye")
        vm.onCheckAnswer()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        val phase = assertIs<TypePhase.Answering>(state.typePhase)
        assertEquals(TypeMiss("goodbye", TypedAnswerOutcome.Wrong), phase.lastMiss)
        assertTrue(state.answerHidden, "a wrong answer gave the answer away")
        assertFalse(state.gradesAvailable)
        // Left in the field, so a near miss can be corrected rather than retyped.
        assertEquals("goodbye", state.typedAnswer)
    }

    @Test
    fun aWrongAnswerDoesNotFlipACardThatWasStillFaceDown() = runTest {
        seedTypingDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAnswerChange("goodbye")
        vm.onCheckAnswer()
        advanceUntilIdle()

        assertFalse(assertIs<StudySessionUiState.Reviewing>(vm.state.value).revealed)
    }

    @Test
    fun aSecondTryAfterAMissStillCounts() = runTest {
        seedTypingDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAnswerChange("goodbye")
        vm.onCheckAnswer()
        advanceUntilIdle()
        vm.onAnswerChange("hello")
        vm.onCheckAnswer()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(TypePhase.Correct("hello"), state.typePhase)
        assertTrue(state.gradesAvailable)
    }

    @Test
    fun anAccentSlipIsANearMissAndStillLetsYouFixIt() = runTest {
        // The one case where holding the answer back is the whole point: "check the accents" is
        // a hint to correct what is already in the field, not a verdict.
        seedDeck()
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish", typeEnabled = true)
        srsRepo.due = listOf(testCard("c1", front = "good morning", back = "buenos días"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAnswerChange("buenos dias")
        vm.onCheckAnswer()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        val phase = assertIs<TypePhase.Answering>(state.typePhase)
        assertEquals(TypedAnswerOutcome.NearMiss, phase.lastMiss?.outcome)
        assertTrue(state.answerHidden)

        vm.onAnswerChange("buenos días")
        vm.onCheckAnswer()
        advanceUntilIdle()

        assertIs<TypePhase.Correct>(assertIs<StudySessionUiState.Reviewing>(vm.state.value).typePhase)
    }

    @Test
    fun theAnswerNeedNotRepeatTheCardsParentheticalAside() = runTest {
        // "(formal)" tells the reader which sense the card means; it is not four more words to
        // get exactly right.
        seedDeck()
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish", typeEnabled = true)
        srsRepo.due = listOf(testCard("c1", front = "hola", back = "hello (formal)"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAnswerChange("hello")
        vm.onCheckAnswer()
        advanceUntilIdle()

        assertIs<TypePhase.Correct>(assertIs<StudySessionUiState.Reviewing>(vm.state.value).typePhase)
    }

    @Test
    fun typingTheAsideAnywayIsStillCorrect() = runTest {
        // Whoever reads "(formal)" as part of the answer should not be punished for it either.
        seedDeck()
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish", typeEnabled = true)
        srsRepo.due = listOf(testCard("c1", front = "hola", back = "hello (formal)"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAnswerChange("hello (formal)")
        vm.onCheckAnswer()
        advanceUntilIdle()

        assertIs<TypePhase.Correct>(assertIs<StudySessionUiState.Reviewing>(vm.state.value).typePhase)
    }

    @Test
    fun theRestOfThePhraseIsStillGradedAroundAnAside() = runTest {
        seedDeck()
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish", typeEnabled = true)
        srsRepo.due = listOf(testCard("c1", front = "hola", back = "hello (formal)"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAnswerChange("formal")
        vm.onCheckAnswer()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        val phase = assertIs<TypePhase.Answering>(state.typePhase)
        assertEquals(TypedAnswerOutcome.Wrong, phase.lastMiss?.outcome)
        assertTrue(state.answerHidden)
    }

    @Test
    fun anEmptyCheckIsIgnoredRatherThanScoredWrong() = runTest {
        seedTypingDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAnswerChange("   ")
        vm.onCheckAnswer()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertIs<TypePhase.Answering>(state.typePhase)
        assertTrue(state.answerHidden)
    }

    @Test
    fun givingUpRevealsTheAnswerAndSuggestsNoGrade() = runTest {
        // An escape hatch, not a self-assessment: the four buttons stay equally available and
        // nothing is pre-selected. The phase carries no grade at all, so nothing can.
        seedTypingDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGiveUp()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(TypePhase.GaveUp, state.typePhase)
        assertTrue(state.revealed)
        assertFalse(state.answerHidden)
        assertTrue(state.gradesAvailable)
        assertEquals(setOf(SrsGrade.Again, SrsGrade.Hard, SrsGrade.Good, SrsGrade.Easy), state.intervals.keys)
        assertTrue(srsRepo.reviews.isEmpty(), "giving up graded the card")
    }

    @Test
    fun theSessionCarriesOnAfterGivingUp() = runTest {
        seedTypingDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGiveUp()
        vm.onGrade(SrsGrade.Again)
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(expected = 2, actual = state.position)
        // The next card starts its own answer, with nothing carried over from the last one.
        assertIs<TypePhase.Answering>(state.typePhase)
        assertEquals("", state.typedAnswer)
        assertFalse(state.revealed)
    }

    @Test
    fun aCardWithNoBackTextFallsBackToTapToReveal() = runTest {
        // An image-only answer, which Anki imports produce: an input with nothing to match.
        seedTypingDeck()
        srsRepo.due = listOf(testCard("c1", front = "hola", back = ""))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(TypePhase.Off, assertIs<StudySessionUiState.Reviewing>(vm.state.value).typePhase)

        vm.onReveal()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertFalse(state.answerHidden)
        assertTrue(state.gradesAvailable, "the ordinary flip stopped offering grades")
    }

    @Test
    fun aBackNoAnswerCouldMatchFallsBackToTapToReveal() = runTest {
        // Not blank, so the old isNotBlank() guard let these through — but each normalizes to
        // nothing, so no typed string can ever match. Since a wrong Check no longer reveals,
        // offering the input here would be a dead end with Give up as its only exit.
        for (back in listOf("—", "...", "🇪🇸", "→")) {
            seedTypingDeck()
            srsRepo.due = listOf(testCard("c1", front = "hola", back = back))
            val vm = viewModel()
            advanceUntilIdle()

            val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
            assertEquals(TypePhase.Off, state.typePhase, "typing was offered for a back of '$back'")
            assertFalse(state.answerHidden)

            vm.onReveal()
            advanceUntilIdle()
            assertTrue(
                assertIs<StudySessionUiState.Reviewing>(vm.state.value).gradesAvailable,
                "tap-to-reveal stopped offering grades for a back of '$back'",
            )
        }
    }

    @Test
    fun aCardWithNoPromptFallsBackToTapToReveal() = runTest {
        seedTypingDeck()
        srsRepo.due = listOf(testCard("c1", front = "", back = "hello"))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(TypePhase.Off, assertIs<StudySessionUiState.Reviewing>(vm.state.value).typePhase)
    }

    @Test
    fun theOptInOffLeavesTodaysBehaviourExactly() = runTest {
        seedDeck()
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(TypePhase.Off, assertIs<StudySessionUiState.Reviewing>(vm.state.value).typePhase)

        vm.onReveal()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertFalse(state.answerHidden)
        assertTrue(state.gradesAvailable)
    }

    @Test
    fun aCheckLandingAfterTheQueueAdvancedDoesNotTouchTheNextCard() = runTest {
        // Same hazard onGrade guards: the grade is in flight, so the card is on its way out.
        seedTypingDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGiveUp()
        advanceUntilIdle()
        vm.onGrade(SrsGrade.Good)
        // Deliberately not advanced: the grade job is still running.
        vm.onAnswerChange("hello")
        vm.onCheckAnswer()
        vm.onGiveUp()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(expected = 2, actual = state.position)
        assertIs<TypePhase.Answering>(state.typePhase, "the next card was answered for the user")
        assertEquals("", state.typedAnswer)
    }
}
