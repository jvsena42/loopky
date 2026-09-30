package com.github.jvsena42.loopky.domain.model

import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.localMinuteOfDay

/**
 * Which palette the app paints itself in.
 *
 * A **device** preference rather than a synced one: it describes this screen in this room, and a
 * phone read in bed has no business dimming the tablet on the desk. It lives in
 * `AppPreferences` for that reason and not in `/pub/loopky/settings.json`.
 *
 * PascalCase entries, like every enum that crosses to Swift here — Kotlin exports them lowercased
 * with separators dropped, so a SCREAMING_SNAKE entry arrives under a name nothing can predict.
 */
enum class AppTheme {
    /** Follow the device. The default, and the only value that stays right when the device flips at sunset. */
    System,

    /**
     * Follow the clock instead of the device — see [DayNightSchedule]. Shown to the reader as
     * "Auto", which is what the entry would be called if it could: `auto` is a **C reserved word**,
     * so Kotlin exports an entry of that name as `auto_` and every Swift call site carries the
     * underscore.
     */
    Scheduled,
    Light,
    Dark,
    ;

    companion object {
        /**
         * [name] as an [AppTheme], falling back to [System] — which is also what a value written
         * by a newer release resolves to, rather than an unreadable preference crashing start-up.
         */
        fun fromNameOrSystem(name: String): AppTheme =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: System
    }
}

/**
 * When [AppTheme.Auto] counts it as night: a fixed local window, deliberately not real twilight.
 *
 * Actual sunset needs the device's location, and asking a flashcards app's users for that so the
 * background can change colour is not a trade worth offering. The hours are stated in the Settings
 * copy instead, so the behaviour is predictable rather than mysterious — which is most of what a
 * reader wants from this setting anyway.
 *
 * Both platforms resolve Auto through here rather than each rolling its own, so a phone and a
 * tablet flip at the same moment.
 */
object DayNightSchedule {
    /**
     * The local hours the dark window runs between, for the Settings copy that names them.
     *
     * Plain `val`s rather than `const val`s so they cross to Swift under these names: a `const val`
     * in an object exports under a mangled one, and the SCREAMING_SNAKE a `const` would need is
     * unpredictable on that side for the same reason enum entries are.
     */
    // MayBeConst: a `const val` in an object is exactly what must not be used here — see above.
    @Suppress("MayBeConst")
    val darkFromHour: Int = 20

    @Suppress("MayBeConst")
    val darkUntilHour: Int = 6

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
    private const val MILLIS_PER_MINUTE = 60_000L

    private val darkFrom = darkFromHour * MINUTES_PER_HOUR
    private val darkUntil = darkUntilHour * MINUTES_PER_HOUR

    /**
     * Both orders, not just the wrapping one Loopky ships.
     *
     * A window that wraps midnight needs an `or` and one that does not needs an `and`, and the
     * `or` alone answers "yes" for every minute of a non-wrapping day. That is not hypothetical
     * tidiness: [darkFromHour] and [darkUntilHour] are the two values anyone reaches for first, and
     * moving them to a daytime window to see the feature work is what found this.
     */
    fun isNightAt(minuteOfDay: Int): Boolean =
        if (darkFrom > darkUntil) {
            minuteOfDay >= darkFrom || minuteOfDay < darkUntil
        } else {
            minuteOfDay in darkFrom until darkUntil
        }

    fun isNightNow(): Boolean = isNightAt(localMinuteOfDay(epochMillis()))

    /**
     * How long until [isNightNow] would answer differently, for a caller that would rather sleep
     * than poll.
     *
     * Never zero: a caller looping on this exactly at the boundary would spin. Callers should still
     * cap the wait — a delay does not run while the process is frozen, so one scheduled hours out
     * fires late by however long the phone was asleep.
     */
    fun millisUntilFlip(): Long {
        val now = localMinuteOfDay(epochMillis())
        val next = if (isNightAt(now)) darkUntil else darkFrom
        val minutes = ((next - now) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return (if (minutes == 0) MINUTES_PER_DAY else minutes) * MILLIS_PER_MINUTE
    }
}
