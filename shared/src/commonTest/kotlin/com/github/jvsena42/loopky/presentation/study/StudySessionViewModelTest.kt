package com.github.jvsena42.loopky.presentation.study

import com.github.jvsena42.loopky.domain.model.ErrorReason
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudySessionViewModelTest {

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

    private val settingsRepo = FakeSettingsRepository()

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

    /** A deck that has declared its pair, so Listen and Speak are actually on offer. */
    private suspend fun seedSpeechDeck() {
        seedDeck()
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            title = "Spanish",
            frontLang = "es-ES",
            backLang = "en-US",
        )
    }

    @Test
    fun loadBuildsTheDueQueueAndShowsTheFirstCardFaceDown() = runTest {
        seedDeck()
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals("Spanish", state.deckTitle)
        assertEquals(expected = 1, actual = state.position)
        assertEquals(expected = 2, actual = state.total)
        assertEquals("hola", state.frontText)
        assertEquals("hello", state.backText)
        assertTrue(!state.revealed)
        assertEquals(SrsGrade.entries.toSet(), state.intervals.keys)
    }

    @Test
    fun revealFlipsTheCurrentCard() = runTest {
        seedDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onReveal()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertTrue(state.revealed)
        assertEquals(expected = 1, actual = state.position)
    }

    @Test
    fun gradingAdvancesToTheNextCardFaceDown() = runTest {
        seedDeck()
        val vm = viewModel()
        advanceUntilIdle()
        vm.onReveal()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(expected = 2, actual = state.position)
        assertEquals("gracias", state.frontText)
        assertTrue(!state.revealed)
        assertEquals(listOf("c1" to SrsGrade.Good), srsRepo.reviews.map { it.first.id to it.second })
    }

    @Test
    fun gradingTheLastCardCompletesTheSession() = runTest {
        seedDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        vm.onGrade(SrsGrade.Again)
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Complete>(vm.state.value)
        assertEquals(expected = 2, actual = state.reviewed)
        assertEquals(listOf(SrsGrade.Good, SrsGrade.Again), srsRepo.reviews.map { it.second })
    }

    @Test
    fun emptyQueueShowsAllCaughtUp() = runTest {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish")
        srsRepo.due = emptyList()
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(StudySessionUiState.Empty("Spanish"), vm.state.value)
    }

    @Test
    fun nullDeckIdStudiesEveryDueCard() = runTest {
        srsRepo.due = listOf(
            testCard("c1", deckId = "deck1"),
            testCard("c9", deckId = "deck2"),
        )
        val vm = viewModel(deckId = null)

        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(expected = 2, actual = state.total)
    }

    // ── sync failures (#91) ──────────────────────────────────────────────

    @Test
    fun aFailedBackgroundFlushWarnsWithoutEndingTheSession() = runTest {
        seedDeck()
        val vm = viewModel()
        runCurrent()

        srsRepo.emitFlushFailure(ErrorReason.StorageFull)
        runCurrent()

        // A warning over the card, not an Error state: the reviews are buffered and journalled, so
        // blanking the card the user is mid-way through would cost them more than the warning.
        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(ErrorReason.StorageFull, state.syncError)
    }

    @Test
    fun theSyncWarningOutlivesTheNextCard() = runTest {
        seedDeck()
        val vm = viewModel()
        runCurrent()
        srsRepo.emitFlushFailure(ErrorReason.StorageFull)
        runCurrent()

        vm.onGrade(SrsGrade.Good)
        runCurrent()

        // emitCurrent rebuilds the state on every card, and a warning the user has not acted on
        // must not vanish because they graded the next one.
        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(ErrorReason.StorageFull, state.syncError)
    }

    @Test
    fun dismissingTheSyncWarningClearsIt() = runTest {
        seedDeck()
        val vm = viewModel()
        runCurrent()
        srsRepo.emitFlushFailure(ErrorReason.StorageFull)
        runCurrent()

        vm.onDismissSyncError()
        runCurrent()

        assertNull(assertIs<StudySessionUiState.Reviewing>(vm.state.value).syncError)
    }

    @Test
    fun reachingTheDailyGoalCelebratesAndStopsNothing() = runTest(mainDispatcher) {
        // The whole point of a soft goal: you are told, and the next card is already there.
        settingsRepo.setStudySettings(StudySettings(newCardsPerDayGoal = 2))
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish")
        // More cards than the goal, so "keep studying" is something the queue can actually offer.
        srsRepo.due = (1..6).map { testCard("c$it", front = "front $it", back = "back $it") }
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        assertNull(
            assertIs<StudySessionUiState.Reviewing>(vm.state.value).goalCelebration,
            "celebrated before the goal was reached",
        )

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        val reached = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        val celebration = assertNotNull(reached.goalCelebration, "the goal was not celebrated")
        assertEquals(expected = 2, actual = celebration.newCardsToday)
        assertEquals(expected = 2, actual = celebration.goal)
        assertTrue(reached.total > reached.position - 1, "the queue was cut short at the goal")

        // Keep studying dismisses it and the session carries on.
        vm.onContinueAfterGoal()
        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        assertNull(assertIs<StudySessionUiState.Reviewing>(vm.state.value).goalCelebration)
    }

    @Test
    fun aGoalMetOnTheLastCardIsCelebratedThenAndNotInTheNextSession() = runTest(mainDispatcher) {
        // Skipping it here left it owed, so it popped up one card into the next session — on a
        // phone, typically only after the app had been restarted.
        settingsRepo.setStudySettings(StudySettings(newCardsPerDayGoal = 1))
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish")
        srsRepo.due = listOf(testCard("c1", front = "hola", back = "hello"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()

        val done = assertIs<StudySessionUiState.Complete>(vm.state.value)
        val celebration = assertNotNull(done.goalCelebration, "the goal was met but not celebrated")
        assertEquals(expected = 1, actual = celebration.newCardsToday)
        assertTrue(srsRepo.dailyProgress.value.goalCelebrated, "the celebration was left owed")

        // Dismissing it uncovers the summary, which is still there underneath.
        vm.onContinueAfterGoal()
        assertNull(assertIs<StudySessionUiState.Complete>(vm.state.value).goalCelebration)

        srsRepo.due = (2..4).map { testCard("c$it", front = "front $it", back = "back $it") }
        val next = viewModel()
        advanceUntilIdle()
        next.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        assertNull(
            assertIs<StudySessionUiState.Reviewing>(next.state.value).goalCelebration,
            "the celebration came back in the next session",
        )
    }

    @Test
    fun theGoalIsCelebratedOncePerDayNotOncePerSession() = runTest(mainDispatcher) {
        // A flag on the ViewModel would congratulate the user again every time they reopened the
        // study screen, for a goal they met an hour ago.
        settingsRepo.setStudySettings(StudySettings(newCardsPerDayGoal = 1))
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish")
        srsRepo.due = (1..4).map { testCard("c$it", front = "front $it", back = "back $it") }

        val first = viewModel()
        advanceUntilIdle()
        first.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        assertNotNull(assertIs<StudySessionUiState.Reviewing>(first.state.value).goalCelebration)

        // A second session on the same day, well past the goal.
        val second = viewModel()
        advanceUntilIdle()
        second.onGrade(SrsGrade.Good)
        advanceUntilIdle()

        assertNull(
            assertIs<StudySessionUiState.Reviewing>(second.state.value).goalCelebration,
            "the celebration came back in a later session on the same day",
        )
    }

    @Test
    fun aGoalAlreadyMetEarlierInTheDayIsStillCelebratedOnce() = runTest(mainDispatcher) {
        // Threshold, not delta: the crossing can happen in a session killed before it renders, and
        // lowering the goal below what you have already done counts as meeting it.
        settingsRepo.setStudySettings(StudySettings(newCardsPerDayGoal = 3))
        srsRepo.setDailyProgress(newCards = 9, reviews = 9)
        seedDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()

        assertNotNull(assertIs<StudySessionUiState.Reviewing>(vm.state.value).goalCelebration)
    }

    @Test
    fun theCongratsScreenSaysWhenTheNextReviewLands() = runTest(mainDispatcher) {
        // "All done! 🎊 / You reviewed 4 cards." said nothing about what happens next (#101 §5).
        srsRepo.nextDue = 1_234L
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish")
        srsRepo.due = listOf(testCard("c1", front = "hola", back = "hello"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()

        val complete = assertIs<StudySessionUiState.Complete>(vm.state.value)
        assertEquals(expected = 1_234L, actual = complete.nextDueAtMillis)
    }

    // ── listen / speak ───────────────────────────────────────────────────

    @Test
    fun listenReadsTheFacingSideInThatSidesLanguage() = runTest {
        // The two sides of a vocabulary card are routinely different languages, so the tag has to
        // follow the flip rather than being fixed per deck.
        seedSpeechDeck()
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onSpeak()
        advanceUntilIdle()
        assertEquals(StudySessionEffect.Speak("hola", "es-ES"), effects.excludingHaptics().single())

        vm.onReveal()
        vm.onSpeak()
        advanceUntilIdle()
        assertEquals(StudySessionEffect.Speak("hello", "en-US"), effects.excludingHaptics().last())

        job.cancel()
    }

    @Test
    fun listenReadsThePhraseAndNotTheCardsAside() = runTest {
        // Handed "hola (formal)", the engine reads the editorial note out as a word.
        seedSpeechDeck()
        srsRepo.due = listOf(testCard("c1", front = "hola (formal)", back = "hello (formal)"))
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onSpeak()
        advanceUntilIdle()
        assertEquals(StudySessionEffect.Speak("hola", "es-ES"), effects.excludingHaptics().single())

        vm.onReveal()
        vm.onSpeak()
        advanceUntilIdle()
        assertEquals(StudySessionEffect.Speak("hello", "en-US"), effects.excludingHaptics().last())

        job.cancel()
    }

    @Test
    fun speakPracticeListensInTheBackLanguage() = runTest {
        // The target is always the card back, so an en-US model would be grading Spanish speech.
        seedSpeechDeck()
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onReveal()
        advanceUntilIdle()
        vm.onSpeakTest()
        advanceUntilIdle()

        assertEquals(
            StudySessionEffect.StartSpeechRecognition("hello", "en-US"),
            effects.excludingHaptics().single(),
        )
        job.cancel()
    }

    @Test
    fun speakPracticeOnTheFrontTargetsTheFrontSide() = runTest {
        // Both buttons show on both sides, and on the front the thing to
        // pronounce is the prompt — grading it against the back would mark every attempt wrong.
        seedSpeechDeck()
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onSpeakTest()
        advanceUntilIdle()

        assertEquals(
            StudySessionEffect.StartSpeechRecognition("hola", "es-ES"),
            effects.excludingHaptics().single(),
        )
        job.cancel()
    }

    @Test
    fun theListeningPromptShowsTheSideBeingGraded() = runTest {
        // The sheet used to prompt with the back text whichever side was facing, so practising the
        // front asked for the answer and then failed it against the prompt.
        seedSpeechDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSpeakTest()
        advanceUntilIdle()

        val front = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(SpeakPhase.Listening("hola"), front.speakPhase)

        vm.onSpeakDismiss()
        vm.onReveal()
        vm.onSpeakTest()
        advanceUntilIdle()

        val back = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(SpeakPhase.Listening("hello"), back.speakPhase)
    }

    @Test
    fun flippingTheCardMidListenDoesNotChangeWhatIsGraded() = runTest {
        // The transcript arrives asynchronously. Re-deriving the target on arrival would grade a
        // correctly spoken prompt against the answer the user flipped to while talking.
        seedSpeechDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSpeakTest()
        advanceUntilIdle()
        vm.onReveal()
        advanceUntilIdle()
        vm.onSpeechResult("hola")
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertIs<SpeakPhase.Correct>(state.speakPhase)
    }

    @Test
    fun retryingKeepsTheSideTheAttemptStartedOn() = runTest {
        seedSpeechDeck()
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onSpeakTest()
        advanceUntilIdle()
        vm.onSpeechResult("adios")
        advanceUntilIdle()
        vm.onSpeakRetry()
        advanceUntilIdle()

        assertEquals(
            StudySessionEffect.StartSpeechRecognition("hola", "es-ES"),
            effects.excludingHaptics().last(),
        )
        job.cancel()
    }

    @Test
    fun aTranscriptThatCameBackAsDigitsStillMatchesASpelledCard() = runTest {
        // The recognizer decides between "diez" and "10"; the speaker said the same thing either
        // way. Graded in the *target side's* language, which is what makes the fold safe.
        seedSpeechDeck()
        srsRepo.due = listOf(testCard("c1", front = "diez", back = "ten"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSpeakTest()
        advanceUntilIdle()
        vm.onSpeechResult("10")
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertIs<SpeakPhase.Correct>(state.speakPhase)
    }

    @Test
    fun aRecognitionFailureIsReportedInTheSheetRatherThanClosingIt() = runTest {
        // A sheet that vanishes on ERROR_NO_MATCH is indistinguishable from the app having dropped
        // the tap — which is exactly how the bug was reported.
        seedSpeechDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSpeakTest()
        advanceUntilIdle()
        vm.onSpeechError(SpeechError.NoMatch)
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        val failed = assertIs<SpeakPhase.Failed>(state.speakPhase)
        assertEquals(SpeechError.NoMatch, failed.reason)
        assertEquals("hola", failed.expected)
        assertTrue(failed.retryable, "Nothing matched — another go is the whole point")
    }

    @Test
    fun retryingAfterAFailureKeepsTheSameTarget() = runTest {
        seedSpeechDeck()
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onSpeakTest()
        advanceUntilIdle()
        vm.onSpeechError(SpeechError.Busy)
        advanceUntilIdle()
        vm.onSpeakRetry()
        advanceUntilIdle()

        assertEquals(StudySessionEffect.StartSpeechRecognition("hola", "es-ES"), effects.excludingHaptics().last())
        job.cancel()
    }

    @Test
    fun aFailureNothingCanRetryOffersNoRetry() = runTest {
        // A refused permission and a missing language model do not change by trying again, so the
        // sheet offers a way out instead of a button that repeats the same failure.
        seedSpeechDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSpeakTest()
        advanceUntilIdle()
        vm.onSpeechError(SpeechError.LanguageUnavailable)
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertFalse(assertIs<SpeakPhase.Failed>(state.speakPhase).retryable)
    }

    @Test
    fun aLateFailureAfterDismissalIsIgnored() = runTest {
        // The mirror of the late-transcript guard: an error landing after the sheet is gone must
        // not reopen it over a card the user has moved on from.
        seedSpeechDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSpeakTest()
        advanceUntilIdle()
        vm.onSpeakDismiss()
        vm.onSpeechError(SpeechError.NoMatch)
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(SpeakPhase.Idle, state.speakPhase)
    }

    @Test
    fun aLateTranscriptAfterDismissalIsIgnored() = runTest {
        // Dismissing clears the target; without that guard a result landing afterwards would
        // reopen the Correct/Wrong sheet over a card the user has moved on from.
        seedSpeechDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSpeakTest()
        advanceUntilIdle()
        vm.onSpeakDismiss()
        vm.onSpeechResult("hola")
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(SpeakPhase.Idle, state.speakPhase)
    }

    @Test
    fun aDeckWithNoDeclaredPairOffersNeitherFeature() = runTest {
        // testDeck leaves the languages unset, which is what every deck published before they
        // existed looks like. The opt-ins default true, so only speechReady stops them.
        seedDeck()
        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertFalse(state.listenEnabled, "Listen was offered with no language to read in")
        assertFalse(state.speakEnabled, "Speak was offered with no language to listen for")
    }

    @Test
    fun withoutALanguageNeitherActionEmitsAnything() = runTest {
        // Gated on the action, not only in the UI: otherwise the engine falls back to the
        // reader's own locale and mispronounces the card.
        seedDeck()
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onSpeak()
        vm.onReveal()
        advanceUntilIdle()
        vm.onSpeak()
        vm.onSpeakTest()
        vm.onSpeakRetry()
        advanceUntilIdle()

        assertEquals(emptyList(), effects)
        job.cancel()
    }

    @Test
    fun anOptedOutDeckStaysOptedOutEvenWithLanguages() = runTest {
        seedDeck()
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            title = "Spanish",
            frontLang = "es-ES",
            backLang = "en-US",
        ).copy(listenEnabled = false, speakEnabled = false)
        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertFalse(state.listenEnabled)
        assertFalse(state.speakEnabled)
    }
}
