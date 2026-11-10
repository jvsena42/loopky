package com.github.jvsena42.loopky.domain.model

import kotlin.math.roundToInt

/**
 * How fast Listen reads a card: a multiplier of the engine's normal rate, chosen in Settings as
 * the speed every card starts at. Repeat taps on the same text step down from it by [REPEAT_STEP]
 * and stop at [MIN].
 *
 * [MIN] is not arbitrary: iOS maps the multiplier onto `AVSpeechUtterance.rate`, whose floor is
 * about half the default speed, so anything below 0.5 would sound the same as 0.5 there.
 *
 * Plain `val`s rather than `const val`: a `const` in an object crosses to Swift under a mangled
 * name.
 */
@Suppress("MayBeConst")
object ListenSpeed {
    val MIN: Float = 0.5f
    val MAX: Float = 1f
    val STEP: Float = 0.05f
    val DEFAULT: Float = 1f
    val REPEAT_STEP: Float = 0.25f

    /** The slider's stops between [MIN] and [MAX], not counting either end. */
    val SLIDER_STEPS: Int = ((MAX - MIN) / STEP).roundToInt() - 1

    /** Into range and onto a [STEP] stop, so a stored value always matches a slider position. */
    fun sanitized(rate: Float): Float {
        if (rate.isNaN()) return DEFAULT
        val stops = (rate.coerceIn(MIN, MAX) / STEP).roundToInt()
        return (stops * STEP * 100).roundToInt() / 100f
    }

    /** The rate for the [repeats]th consecutive Listen of the same text, 0 being the first. */
    fun forRepeat(base: Float, repeats: Int): Float =
        (sanitized(base) - REPEAT_STEP * repeats).coerceAtLeast(MIN)

    /** Whether another repeat would still change the rate — the point past which counting stops. */
    fun canSlowFurther(base: Float, repeats: Int): Boolean = forRepeat(base, repeats) > MIN
}
