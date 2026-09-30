package com.github.jvsena42.loopky.presentation.study

import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.domain.model.StudySettings
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
 * Studying a deck both ways (#46 §2): one card record, one review state, two presentations.
 *
 * Split out of [StudySessionViewModelTest] the way the typing tests were — same fakes, same
 * dispatcher setup, only the mode under test differs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudySessionReverseTest {

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

    /** Two cards in a deck opted into both directions, with a language pair declared. */
    private fun seedReverseDeck(reverseEnabled: Boolean = true) {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            title = "Spanish",
            frontLang = "es-ES",
            backLang = "en-US",
            reverseEnabled = reverseEnabled,
        )
        srsRepo.due = listOf(
            testCard("c1", front = "perro", back = "dog"),
            testCard("c2", front = "gato", back = "cat"),
        )
    }

    private fun reviewing(vm: StudySessionViewModel) =
        assertIs<StudySessionUiState.Reviewing>(vm.state.value)

    /** Walk to the first reversed presentation, grading each forward card [grade] on the way. */
    private suspend fun advanceToReverse(
        vm: StudySessionViewModel,
        grade: SrsGrade,
        advance: suspend () -> Unit,
    ): StudySessionUiState.Reviewing {
        repeat(MAX_STEPS) {
            val state = reviewing(vm)
            if (state.reversed) return state
            vm.onReveal()
            advance()
            vm.onGrade(grade)
            advance()
        }
        error("No reversed presentation appeared within $MAX_STEPS cards")
    }

    @Test
    fun aDeckThatDidNotOptInIsStudiedOneWayOnly() = runTest {
        seedReverseDeck(reverseEnabled = false)
        val vm = viewModel()
        advanceUntilIdle()

        val state = reviewing(vm)
        assertEquals(2, state.total, "the queue was paired for a deck that never asked")
        assertFalse(state.reversed)
    }

    @Test
    fun anOptedInDeckAsksEveryCardTwice() = runTest {
        seedReverseDeck()
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(4, reviewing(vm).total)
    }

    @Test
    fun theReversedPresentationSwapsTheSidesAndTheirLanguages() = runTest {
        seedReverseDeck()
        val vm = viewModel()
        advanceUntilIdle()

        val forward = reviewing(vm)
        assertEquals("perro", forward.frontText)
        assertEquals("dog", forward.backText)
        assertEquals("es-ES", forward.frontLang)
        assertEquals("en-US", forward.backLang)

        val reverse = advanceToReverse(vm, SrsGrade.Good) { advanceUntilIdle() }
        assertEquals("dog", reverse.frontText, "the reverse asked the same way round")
        assertEquals("perro", reverse.backText)
        // The language belongs to the face, not the slot: read with the front's Spanish voice, an
        // English prompt comes out in a Spanish accent.
        assertEquals("en-US", reverse.frontLang)
        assertEquals("es-ES", reverse.backLang)
        assertEquals("dog", reverse.backLabel, "the prompt label still quotes what was asked")
    }

    @Test
    fun listenReadsThePromptOfAReversedCardNotTheCardsOwnFront() = runTest {
        seedReverseDeck()
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            frontLang = "es-ES",
            backLang = "en-US",
            reverseEnabled = true,
        )
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<StudySessionEffect>()
        val job = launch { vm.effects.toList(effects) }

        advanceToReverse(vm, SrsGrade.Good) { advanceUntilIdle() }
        vm.onSpeak()
        advanceUntilIdle()

        val spoken = assertIs<StudySessionEffect.Speak>(effects.excludingHaptics().last())
        assertEquals("dog", spoken.text)
        assertEquals("en-US", spoken.languageTag, "read in the wrong side's language")
        job.cancel()
    }

    @Test
    fun typingOnAReversedCardIsCheckedAgainstTheCardsFront() = runTest {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            typeEnabled = true,
            reverseEnabled = true,
        )
        srsRepo.due = listOf(testCard("c1", front = "perro", back = "dog"))
        val vm = viewModel()
        advanceUntilIdle()

        // Forward: the answer is the back.
        vm.onAnswerChange("dog")
        vm.onCheckAnswer()
        advanceUntilIdle()
        assertIs<TypePhase.Correct>(reviewing(vm).typePhase)
        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()

        // Reversed: the answer is now the front, and the old answer is wrong.
        assertTrue(reviewing(vm).reversed)
        vm.onAnswerChange("dog")
        vm.onCheckAnswer()
        advanceUntilIdle()
        assertIs<TypePhase.Answering>(reviewing(vm).typePhase)

        vm.onAnswerChange("perro")
        vm.onCheckAnswer()
        advanceUntilIdle()
        assertIs<TypePhase.Correct>(reviewing(vm).typePhase)
    }

    @Test
    fun aWorseReverseReschedulesTheCardFromWhereThePairStarted() = runTest {
        // The pair lands on its weaker direction — and from the pre-pair state, not on top of the
        // forward result, which would compound two reviews out of one.
        seedReverseDeck()
        srsRepo.due = listOf(testCard("c1", front = "perro", back = "dog"))
        val before = SrsState(
            cardId = "c1",
            dueAt = 0L,
            intervalDays = 10,
            easeFactor = 2.5,
            repetitions = 4,
            lastGrade = SrsGrade.Good,
        )
        srsRepo.upsert("deck1", before)

        val vm = viewModel()
        advanceUntilIdle()
        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        vm.onGrade(SrsGrade.Again)
        advanceUntilIdle()

        assertEquals(1, srsRepo.reviewsFrom.size, "the reverse did not re-schedule the card")
        val (card, base, grade) = srsRepo.reviewsFrom.single()
        assertEquals("c1", card.id)
        assertEquals(before, base, "re-scheduled from the forward result, not from the pair's start")
        assertEquals(SrsGrade.Again, grade)
    }

    @Test
    fun anEqualOrBetterReverseLeavesTheForwardScheduleAlone() = runTest {
        seedReverseDeck()
        srsRepo.due = listOf(testCard("c1", front = "perro", back = "dog"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Hard)
        advanceUntilIdle()
        val afterForward = srsRepo.states["c1"]
        vm.onGrade(SrsGrade.Easy)
        advanceUntilIdle()

        assertTrue(srsRepo.reviewsFrom.isEmpty(), "a better reverse rewrote the card")
        assertEquals(afterForward, srsRepo.states["c1"])
        assertEquals(1, srsRepo.reviews.size, "the pair was graded twice")
    }

    @Test
    fun aSessionAbandonedBeforeTheReverseKeepsTheForwardGrade() = runTest {
        // The forward half is written as it happens, which is the whole reason it is not deferred.
        seedReverseDeck()
        srsRepo.due = listOf(testCard("c1", front = "perro", back = "dog"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        vm.onClose()
        advanceUntilIdle()

        val stored = srsRepo.states.getValue("c1")
        assertEquals(SrsGrade.Good, stored.lastGrade)
        assertEquals(1, stored.repetitions)
    }

    @Test
    fun theEndOfSessionTallyCountsCardsNotPresentations() = runTest {
        seedReverseDeck()
        val vm = viewModel()
        advanceUntilIdle()

        repeat(4) {
            vm.onGrade(SrsGrade.Good)
            advanceUntilIdle()
        }

        val done = assertIs<StudySessionUiState.Complete>(vm.state.value)
        assertEquals(2, done.reviewed, "four presentations were reported as four cards")
    }

    @Test
    fun aSmallDailyGoalPullsTheReverseForwardWithoutWithholdingAnything() = runTest {
        // Placement, not capping: the queue is still every card, twice.
        settingsRepo.setStudySettings(StudySettings(newCardsPerDayGoal = 2))
        deckRepo.decks["deck1"] = testDeck(id = "deck1", reverseEnabled = true)
        srsRepo.due = (1..6).map { testCard("c$it") }
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(12, reviewing(vm).total)
        // Gap 2: c1, c2, c3, ↔c1 — the third grade lands on the reverse of the first card.
        repeat(3) {
            vm.onGrade(SrsGrade.Good)
            advanceUntilIdle()
        }
        val state = reviewing(vm)
        assertTrue(state.reversed, "the reverse was still five cards out at a goal of two")
        assertEquals("front of c1", state.backText)
    }

    private companion object {
        /** Well past any gap this can produce, so a broken queue fails rather than loops. */
        const val MAX_STEPS = 12
    }
}
