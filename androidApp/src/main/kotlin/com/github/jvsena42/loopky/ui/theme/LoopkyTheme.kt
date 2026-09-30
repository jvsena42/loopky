package com.github.jvsena42.loopky.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.github.jvsena42.loopky.domain.model.AppTheme
import com.github.jvsena42.loopky.domain.model.DayNightSchedule
import kotlinx.coroutines.delay

private val LocalLoopkyColors: ProvidableCompositionLocal<LoopkyColors> =
    staticCompositionLocalOf { LoopkyLightColors }

object LoopkyTheme {
    val colors: LoopkyColors
        @Composable get() = LocalLoopkyColors.current
}

/**
 * Whether [this] means dark *right now*, asking the system only when the user has not decided.
 *
 * A `@Composable` on purpose: `isSystemInDarkTheme()` reads the configuration, so a user on
 * [AppTheme.System] follows the device flipping at sunset without anything having to notice, and
 * [AppTheme.Scheduled] can hold a clock of its own.
 */
@Composable
fun AppTheme.isDark(): Boolean = when (this) {
    AppTheme.System -> isSystemInDarkTheme()
    AppTheme.Scheduled -> isScheduledDark()
    AppTheme.Light -> false
    AppTheme.Dark -> true
}

/**
 * [DayNightSchedule], re-asked as the evening arrives rather than only when a screen happens to
 * recompose — otherwise the app stays light past eight until something else moved.
 *
 * The wait is capped at a minute even when the flip is hours away, because `delay` does not run
 * while the process is frozen: a sleep scheduled for 20:00 at noon fires whenever the phone next
 * wakes, which may be long after. Polling a foreground app once a minute costs nothing and bounds
 * the staleness at that minute.
 */
@Composable
private fun isScheduledDark(): Boolean {
    val dark by produceState(DayNightSchedule.isNightNow()) {
        while (true) {
            delay(DayNightSchedule.millisUntilFlip().coerceAtMost(SCHEDULE_POLL_MILLIS))
            value = DayNightSchedule.isNightNow()
        }
    }
    return dark
}

private const val SCHEDULE_POLL_MILLIS = 60_000L

/**
 * Loopky's own palette **and** Material's, from one decision.
 *
 * Both are provided because the app draws in both vocabularies: brand surfaces come from
 * [LoopkyTheme.colors], while every Material component reached for under the native-first rule —
 * `Scaffold`, `ShortNavigationBar`, `ExposedDropdownMenuBox`, an `AlertDialog` — takes its own
 * from the `MaterialTheme` scheme. Setting only one of them leaves a dropdown menu or a dialog
 * drawing light chrome over a dark screen, which is the same split that made iOS Settings
 * unreadable before the palette existed.
 */
@Composable
fun LoopkyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) LoopkyDarkColors else LoopkyLightColors
    val material = if (darkTheme) {
        darkColorScheme(
            primary = colors.accentPrimary,
            onPrimary = colors.foregroundOnAccent,
            background = colors.surfacePrimary,
            onBackground = colors.foregroundPrimary,
            surface = colors.surfacePrimary,
            onSurface = colors.foregroundPrimary,
            // The container a menu, a dialog or a dropdown draws itself on. Left at Material's own
            // near-black it sits *behind* the app's plum surfaces rather than above them.
            surfaceContainer = colors.surfaceCard,
            surfaceContainerHigh = colors.surfaceCard,
            outline = colors.foregroundMuted,
            outlineVariant = colors.borderSubtle,
            error = colors.danger,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = colors.accentPrimary,
            onPrimary = colors.foregroundOnAccent,
            background = colors.surfacePrimary,
            onBackground = colors.foregroundPrimary,
            surface = colors.surfacePrimary,
            onSurface = colors.foregroundPrimary,
            error = colors.danger,
            onError = Color.White,
        )
    }
    CompositionLocalProvider(LocalLoopkyColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
