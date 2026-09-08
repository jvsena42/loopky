package com.github.jvsena42.loopky.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.github.jvsena42.loopky.R

/**
 * Coarse "in 4h" / "in 2d" phrasing — an exact timestamp would be noise wherever this is used.
 *
 * Shared by Home's caught-up card and the study session's congrats screen, which say the same
 * thing about the same clock and so must not round it two different ways.
 */
@Composable
fun relativeFromNow(millis: Long): String {
    val delta = (millis - System.currentTimeMillis()).coerceAtLeast(0L)
    val minutes = delta / MINUTE_MS
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        days > 0 -> pluralStringResource(R.plurals.duration_days, roundUp(delta, DAY_MS), roundUp(delta, DAY_MS))
        hours > 0 -> pluralStringResource(R.plurals.duration_hours, roundUp(delta, HOUR_MS), roundUp(delta, HOUR_MS))
        else -> pluralStringResource(R.plurals.duration_minutes, minutes.toInt(), minutes.toInt())
    }
}

/**
 * Rounded up, not truncated. A card scheduled seven days out is read a few seconds later, so
 * flooring turned the "7d" the user had just tapped into "Next review in 6 days".
 */
private fun roundUp(delta: Long, unit: Long): Int = ((delta + unit - 1) / unit).toInt()

private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val HOUR_MS = MINUTE_MS * MINUTES_PER_HOUR
private const val DAY_MS = HOUR_MS * HOURS_PER_DAY
