package com.github.jvsena42.loopky.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import com.github.jvsena42.loopky.domain.model.AppTheme

/**
 * Tells the *framework* which of its two themes Loopky is currently drawing itself in.
 *
 * Loopky does not need this to paint — [LoopkyTheme] takes the palette straight from the
 * preference. What it buys is the one surface the app cannot reach: the **splash window**, drawn
 * from `Theme.Loopky.Starting` before `MainActivity` exists, and therefore resolved against
 * `values/` or `values-night/` by whatever the system believes. Without this, a user who chose
 * Dark on a light phone gets a cream splash and then a dark app on every cold launch.
 *
 * [darkNow] rather than [theme] alone, because [AppTheme.Scheduled] has no framework equivalent that
 * Loopky can configure — `MODE_NIGHT_CUSTOM` runs on the *system's* schedule, and its hours need a
 * permission only a system app has. Handing over the resolved answer keeps the splash on Loopky's
 * own schedule instead of a different one that happens to have a similar name.
 *
 * API 31+ only; below that the framework has no per-application night mode and the mismatch stands.
 */
internal fun Context.applyApplicationNightMode(theme: AppTheme, darkNow: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val manager = getSystemService<UiModeManager>() ?: return
    // MODE_NIGHT_AUTO is how this API spells "no application override" — it hands the decision
    // back to the device, which is exactly what [AppTheme.System] means.
    val mode = when {
        theme == AppTheme.System -> UiModeManager.MODE_NIGHT_AUTO
        darkNow -> UiModeManager.MODE_NIGHT_YES
        else -> UiModeManager.MODE_NIGHT_NO
    }
    runCatching { manager.setApplicationNightMode(mode) }
}
