package com.github.jvsena42.loopky.domain.model

import kotlin.math.max

/** The number of never-seen cards a day of study aims at, until the user picks their own. */
const val DEFAULT_NEW_CARDS_PER_DAY = 10

private const val MIN_GOAL = 1
private const val MAX_GOAL = 999
private const val MIN_INTERVAL_DAYS = 1
private const val MAX_INTERVAL_DAYS = 365

/**
 * The user's own scheduling preferences, synced through
 * `com.github.jvsena42.loopky.data.repository.SettingsRepository` so a second device schedules the
 * same way this one does.
 *
 * [newCardsPerDayGoal] is a **goal, not a cap**: reaching it is announced and nothing is withheld
 * afterwards. Nothing in the queue-building path may consult it.
 *
 * [hardDays], [goodDays] and [easyDays] are the whole scheduler: a card graded Hard comes back in
 * [hardDays], every time, however often it has been seen. They are what the study screen prints on
 * the buttons, and — unlike under the old compounding rule — what the buttons print is what the
 * card gets. See `SrsScheduler.review`.
 */
data class StudySettings(
    val newCardsPerDayGoal: Int = DEFAULT_NEW_CARDS_PER_DAY,
    val hardDays: Int = 1,
    val goodDays: Int = 3,
    val easyDays: Int = 7,
) {
    /** Clamps every field into a range the scheduler can survive. Applied on read *and* on write. */
    fun sanitized(): StudySettings = StudySettings(
        newCardsPerDayGoal = newCardsPerDayGoal.coerceIn(MIN_GOAL, MAX_GOAL),
        hardDays = hardDays.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS),
        goodDays = goodDays.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS),
        easyDays = easyDays.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS),
    )

    companion object {
        val Default = StudySettings()
        val GOAL_RANGE = MIN_GOAL..MAX_GOAL
        val INTERVAL_RANGE = MIN_INTERVAL_DAYS..MAX_INTERVAL_DAYS
    }
}

/**
 * Where the maturity line sits for *this* user: their longest configured interval.
 *
 * It has to be **reachable**, and that is the whole constraint. Intervals no longer compound, so
 * the furthest out any card can ever sit is the largest of the three settings — a fixed 21-day
 * line (SM-2's, which this used to floor at) would make `masteryShare` cap below 100% and
 * `isFullyMastered` return false forever, on every deck, with nothing reporting it. At 1/2/7 the
 * old formula gave 21 and no card could pass 7.
 *
 * What it now means in practice: a card counts as mastered once it is sitting on the longest
 * interval — normally, once you last graded it Easy. `maxOf` rather than [easyDays] because
 * ordering is not enforced; someone may set Hard higher than Easy.
 */
val StudySettings.maturityThresholdDays: Int
    get() = max(1, maxOf(hardDays, goodDays, easyDays))
