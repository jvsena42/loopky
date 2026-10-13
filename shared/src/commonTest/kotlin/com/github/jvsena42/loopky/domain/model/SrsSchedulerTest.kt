package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("MagicNumber") // Interval/ease literals are the assertions under test.
class SrsSchedulerTest {

    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    private fun state(
        intervalDays: Int,
        repetitions: Int,
        dueAt: Long = now,
        easeFactor: Double = DEFAULT_EASE,
    ): SrsState =
        SrsState(
            cardId = "c",
            dueAt = dueAt,
            intervalDays = intervalDays,
            easeFactor = easeFactor,
            repetitions = repetitions,
            lastGrade = SrsGrade.Good,
        )

    @Test
    fun newCardIsNewAndNotDue() {
        // Nothing about a card you have never met is late. Counting it as due is what made a fresh
        // import open on "1669 due" (#101 §7).
        assertTrue((null as SrsState?).isNew())
        assertFalse((null as SrsState?).isDue(now))
    }

    @Test
    fun aGradedCardIsNoLongerNew() {
        assertFalse(state(intervalDays = 1, repetitions = 1).isNew())
    }

    @Test
    fun futureCardIsNotDue() {
        val s = state(intervalDays = 1, repetitions = 1, dueAt = now + day)
        assertFalse(s.isDue(now))
        assertTrue(s.isDue(now + 2 * day))
    }

    @Test
    fun newCardFirstReviewMatchesDesignIntervals() {
        assertEquals(0, (null as SrsState?).review("c", SrsGrade.Again, now).intervalDays)
        assertEquals(1, (null as SrsState?).review("c", SrsGrade.Hard, now).intervalDays)
        assertEquals(3, (null as SrsState?).review("c", SrsGrade.Good, now).intervalDays)
        assertEquals(7, (null as SrsState?).review("c", SrsGrade.Easy, now).intervalDays)
    }

    @Test
    fun reviewUsesTheUsersOwnIntervals() {
        val settings = StudySettings(hardDays = 2, goodDays = 5, easyDays = 30)
        assertEquals(2, (null as SrsState?).review("c", SrsGrade.Hard, now, settings).intervalDays)
        assertEquals(5, (null as SrsState?).review("c", SrsGrade.Good, now, settings).intervalDays)
        assertEquals(30, (null as SrsState?).review("c", SrsGrade.Easy, now, settings).intervalDays)
        assertEquals(now + 30 * day, (null as SrsState?).review("c", SrsGrade.Easy, now, settings).dueAt)
    }

    @Test
    fun theIntervalIsTheSettingHoweverOftenTheCardHasBeenSeen() {
        // The point of the fixed-interval scheduler: a 2-day Good is 2 days on review 1 and on
        // review 50. Nothing about the card's history is an input.
        val settings = StudySettings(goodDays = 2)
        val seen = listOf(
            null,
            state(intervalDays = 2, repetitions = 1),
            state(intervalDays = 2, repetitions = 12),
            state(intervalDays = 2, repetitions = 50, easeFactor = MIN_EASE),
        )
        seen.forEach { current ->
            val next = current.review("c", SrsGrade.Good, now, settings)
            assertEquals(2, next.intervalDays)
            assertEquals(now + 2 * day, next.dueAt)
        }
    }

    @Test
    fun changingASettingDoesNotMoveAnAlreadyScheduledCard() {
        // Not retroactive: the stored dueAt stands until the card is graded again.
        val scheduled = state(intervalDays = 3, repetitions = 2, dueAt = now + 3 * day)
        assertEquals(now + 3 * day, scheduled.dueAt)
        assertEquals(90, scheduled.review("c", SrsGrade.Good, now, StudySettings(goodDays = 90)).intervalDays)
    }

    @Test
    fun repetitionsAndEaseAreStillTracked() {
        // Nothing schedules from them any more, but they are the record of how the card has gone —
        // and what a return to compounding growth would need.
        val next = state(intervalDays = 3, repetitions = 4).review("c", SrsGrade.Hard, now)
        assertEquals(5, next.repetitions)
        assertTrue(next.easeFactor < DEFAULT_EASE)
    }

    @Test
    fun againResetsIntervalAndDropsEase() {
        val mature = state(intervalDays = 30, repetitions = 5)
        val next = mature.review("c", SrsGrade.Again, now)
        assertEquals(0, next.intervalDays)
        assertEquals(0, next.repetitions)
        assertEquals(now + AGAIN_DELAY_MS, next.dueAt)
        assertTrue(next.easeFactor < mature.easeFactor)
    }

    @Test
    fun easeNeverDropsBelowFloor() {
        var s: SrsState? = null
        repeat(10) { s = s.review("c", SrsGrade.Again, now) }
        assertTrue(s!!.easeFactor >= MIN_EASE)
    }

    @Test
    fun previewLabelsForNewCard() {
        val labels = (null as SrsState?).previewIntervals("c", now)
        assertEquals("<10m", labels[SrsGrade.Again])
        assertEquals("1d", labels[SrsGrade.Hard])
        assertEquals("3d", labels[SrsGrade.Good])
        assertEquals("7d", labels[SrsGrade.Easy])
    }

    @Test
    fun previewLabelsFollowTheUsersIntervals() {
        val labels = (null as SrsState?).previewIntervals("c", now, StudySettings(easyDays = 21))
        assertEquals("21d", labels[SrsGrade.Easy])
    }

    @Test
    fun intervalLabelsAreAlwaysDaysSoTheyEchoTheSetting() {
        // The button prints the number typed in Settings, at every size. 21d used to render "3w"
        // and 30d "4w" — both disagreeing with the setting they are supposed to be showing.
        listOf(1, 21, 29, 30, 60, 365).forEach { days ->
            val labels = (null as SrsState?).previewIntervals("c", now, StudySettings(goodDays = days))
            assertEquals("${days}d", labels[SrsGrade.Good])
        }
    }

    // --- mastery ---------------------------------------------------------------------------

    /** An arbitrary threshold for the share arithmetic; the real one comes from the settings. */
    private val threshold = 21

    private fun states(vararg intervals: Pair<String, Int>): Map<String, SrsState> =
        intervals.associate { (id, days) -> id to state(intervalDays = days, repetitions = 1).copy(cardId = id) }

    @Test
    fun masteryMovesOnTheFirstSession() {
        // The exact case from #101's table, row 4: four cards graded Easy, previously 0%.
        val ids = listOf("a", "b", "c", "d")
        val share = masteryShare(ids, states("a" to 7, "b" to 7, "c" to 7, "d" to 7), threshold)
        assertEquals(expected = 7f / 21f, actual = share, absoluteTolerance = 0.0001f)
    }

    @Test
    fun unseenAndLapsedCardsContributeNothing() {
        val ids = listOf("a", "b")
        assertEquals(0f, masteryShare(ids, emptyMap(), threshold))
        assertEquals(0f, masteryShare(ids, states("a" to 0, "b" to 0), threshold))
    }

    @Test
    fun aCardPastTheThresholdIsCappedAtItsOwnShare() {
        val ids = listOf("a", "b")
        // 'a' at 100 days must not carry 'b' past the pair's fair half.
        assertEquals(0.5f, masteryShare(ids, states("a" to 100, "b" to 0), threshold))
    }

    @Test
    fun masteryEmptyDeckIsZero() {
        assertEquals(0f, masteryShare(emptyList(), emptyMap(), threshold))
    }

    @Test
    fun fullMasteryIsAnExactTestNotAFloatComparison() {
        val ids = listOf("a", "b", "c")
        val mature = states("a" to 21, "b" to 21, "c" to 21)
        // Summing thirds lands just under 1f, which is why isFullyMastered exists.
        assertTrue(masteryShare(ids, mature, threshold) <= 1f)
        assertTrue(isFullyMastered(ids, mature, threshold))
        assertFalse(isFullyMastered(ids, states("a" to 21, "b" to 21, "c" to 20), threshold))
    }

    @Test
    fun anEmptyDeckIsNeverFullyMastered() {
        assertFalse(isFullyMastered(emptyList(), emptyMap(), threshold))
    }

    @Test
    fun theThresholdIsAlwaysReachable() {
        // The constraint the fixed-interval scheduler imposes: no card can ever sit further out
        // than the longest setting, so a threshold above it would pin Mastered % below 100% on
        // every deck forever. The old formula floored at 21 and did exactly that at 1/2/7.
        listOf(
            StudySettings.Default,
            StudySettings(hardDays = 1, goodDays = 2, easyDays = 7),
            StudySettings(easyDays = 2),
            StudySettings(hardDays = 40, goodDays = 3, easyDays = 7),
        ).forEach { settings ->
            val longest = maxOf(settings.hardDays, settings.goodDays, settings.easyDays)
            assertEquals(longest, settings.maturityThresholdDays)
            assertTrue(isFullyMastered(listOf("a"), states("a" to longest), settings.maturityThresholdDays))
        }
    }

    @Test
    fun theThresholdFollowsWhicheverIntervalIsLongest() {
        // Ordering is not enforced, so Hard may legitimately be the longest of the three.
        assertEquals(40, StudySettings(hardDays = 40, goodDays = 3, easyDays = 7).maturityThresholdDays)
    }

    @Test
    fun aLapsedCardFallsAllTheWayBackToUnlearned() {
        // Again zeroes intervalDays, so a mastered card that lapses stops counting — the one way
        // mastery can go down, and the reason it still means something.
        val settings = StudySettings(easyDays = 7)
        val mastered = state(intervalDays = 7, repetitions = 3)
        assertTrue(isFullyMastered(listOf("a"), mapOf("a" to mastered.copy(cardId = "a")), settings.maturityThresholdDays))
        val lapsed = mastered.review("a", SrsGrade.Again, now)
        assertFalse(isFullyMastered(listOf("a"), mapOf("a" to lapsed), settings.maturityThresholdDays))
    }

    // --- settings --------------------------------------------------------------------------

    @Test
    fun sanitizedClampsOutOfRangeValues() {
        val clamped = StudySettings(newCardsPerDayGoal = 0, hardDays = 0, goodDays = 9_999).sanitized()
        assertEquals(1, clamped.newCardsPerDayGoal)
        assertEquals(1, clamped.hardDays)
        assertEquals(365, clamped.goodDays)
    }

    @Test
    fun sanitizedLeavesAValidSettingAlone() {
        val settings = StudySettings(newCardsPerDayGoal = 50, hardDays = 2, goodDays = 4, easyDays = 10)
        assertEquals(settings, settings.sanitized())
    }

    @Test
    fun theDefaultGoalIsTen() {
        assertEquals(10, StudySettings.Default.newCardsPerDayGoal)
    }

    // --- daily progress --------------------------------------------------------------------

    @Test
    fun progressSurvivesWithinTheSameDay() {
        val p = DailyStudyProgress(dayIndex = 100, newCards = 3, reviews = 12)
        assertEquals(p, p.forToday(100))
    }

    @Test
    fun progressZeroesWhenTheDayTurns() {
        val p = DailyStudyProgress(dayIndex = 100, newCards = 3, reviews = 12)
        assertEquals(DailyStudyProgress(dayIndex = 101), p.forToday(101))
    }

    // --- capped interval labels (the reverse half of a paired card) ------------------------

    @Test
    fun anUncappedPreviewIsWhatItAlwaysWas() {
        val base = state(intervalDays = 10, repetitions = 4)

        assertEquals(
            base.previewIntervals("c1", now),
            base.previewIntervals("c1", now, cap = null),
        )
    }

    @Test
    fun aCappedPreviewShowsWhatTheButtonWillActuallySchedule() {
        // The pair lands on its weaker direction, so on the reverse of a card already graded Hard,
        // tapping Easy schedules the Hard interval. Two buttons reading the same is the honest
        // outcome; a label promising more than its button delivers would not be.
        val base = state(intervalDays = 10, repetitions = 4)
        val capped = base.previewIntervals("c1", now, cap = SrsGrade.Hard)
        val hard = base.previewIntervals("c1", now)[SrsGrade.Hard]

        assertEquals(hard, capped[SrsGrade.Hard])
        assertEquals(hard, capped[SrsGrade.Good])
        assertEquals(hard, capped[SrsGrade.Easy])
    }

    @Test
    fun aCappedPreviewLeavesGradesBelowTheCapSpeakingForThemselves() {
        val base = state(intervalDays = 10, repetitions = 4)
        val capped = base.previewIntervals("c1", now, cap = SrsGrade.Good)

        assertEquals("<10m", capped[SrsGrade.Again])
        assertEquals(base.previewIntervals("c1", now)[SrsGrade.Hard], capped[SrsGrade.Hard])
    }

    @Test
    fun cappingAtAgainMakesEveryButtonSayTenMinutes() {
        val base = state(intervalDays = 10, repetitions = 4)
        val capped = base.previewIntervals("c1", now, cap = SrsGrade.Again)

        assertTrue(capped.values.all { it == "<10m" }, "got $capped")
    }
}
