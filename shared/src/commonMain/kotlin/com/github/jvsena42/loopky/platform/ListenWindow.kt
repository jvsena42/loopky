package com.github.jvsena42.loopky.platform

import kotlin.math.roundToLong

/**
 * How long a Speak listen waits: [pauseMillis] of silence after the last words heard ends the
 * answer, and [capMillis] ends the attempt whatever is happening. Both grow with the answer
 * expected, because a 22-word sentence spoken by a learner has pauses in it that a single word
 * does not. A short answer keeps the original 1.5 s / 15 s, and a long sentence reaches 3 s / 60 s.
 */
data class ListenWindow(val pauseMillis: Long, val capMillis: Long) {
    companion object {
        private const val SHORT_PAUSE_MILLIS = 1_500L
        private const val LONG_PAUSE_MILLIS = 3_000L
        private const val SHORT_CAP_MILLIS = 15_000L
        private const val LONG_CAP_MILLIS = 60_000L

        /** Up to this many words the original window applies unchanged. */
        private const val SHORT_WORDS = 3

        /** From this many words on the pause is at its longest. */
        private const val LONG_PAUSE_WORDS = 12
        private const val CAP_MILLIS_PER_WORD = 2_500L

        /**
         * Text written without spaces (Japanese, Chinese) has no words to count, so its length
         * stands in: roughly this many characters to one spoken word.
         */
        private const val CHARS_PER_WORD = 6

        fun forAnswer(expected: String): ListenWindow {
            val extra = (spokenWords(expected) - SHORT_WORDS).coerceAtLeast(0)
            val pauseSpan = LONG_PAUSE_MILLIS - SHORT_PAUSE_MILLIS
            val pauseRamp = (LONG_PAUSE_WORDS - SHORT_WORDS).toDouble()
            val pause = SHORT_PAUSE_MILLIS + (pauseSpan * (extra / pauseRamp).coerceAtMost(1.0)).roundToLong()
            val cap = (SHORT_CAP_MILLIS + extra * CAP_MILLIS_PER_WORD).coerceAtMost(LONG_CAP_MILLIS)
            return ListenWindow(pauseMillis = pause, capMillis = cap)
        }

        private fun spokenWords(text: String): Int {
            val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
            val letters = text.count { !it.isWhitespace() }
            return maxOf(words, (letters + CHARS_PER_WORD - 1) / CHARS_PER_WORD)
        }
    }
}
