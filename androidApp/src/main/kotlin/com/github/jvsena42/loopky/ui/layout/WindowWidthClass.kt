package com.github.jvsena42.loopky.ui.layout

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.window.core.layout.WindowSizeClass

/**
 * How much horizontal room the window has, in the three width buckets Loopky makes decisions on.
 *
 * A thin reading of [WindowSizeClass], which comes from `currentWindowAdaptiveInfo()` — the API
 * Android's adaptive-layout guidance points at, and the reason this is not computed from
 * `LocalConfiguration.screenWidthDp`: the configuration reports the screen, while the adaptive info
 * reports the *window*, and those differ in exactly the cases that matter (split screen, a resizable
 * desktop window on ChromeOS, a folded inner display).
 *
 * Kept as our own enum rather than passing `WindowSizeClass` around because Loopky only ever asks
 * two questions of it — "is there room for a rail" and "how many grid columns" — and because the
 * platform class has no compact/medium/expanded members to `when` over, only breakpoint constants.
 *
 * Read the window, never the device. A tablet in landscape is [Expanded]; the same tablet in
 * portrait — and a large unfolded phone, and Loopky in half a split screen — is [Medium]. Nothing
 * in the UI may ask "is this a tablet": the answer changes when the user rotates the thing or drags
 * a split-screen divider, and the layout has to change with it.
 */
enum class WindowWidthClass {
    /** Phones in portrait. Below 600dp. */
    Compact,

    /** Tablets in portrait, large unfolded phones, split-screen panes. 600dp until 840dp. */
    Medium,

    /** Tablets in landscape, desktop-sized windows. 840dp and up. */
    Expanded,
    ;

    /** True once there is room for a navigation rail beside the content rather than a bar under it. */
    val isExpanded: Boolean get() = this == Expanded

    /** True for anything roomier than a phone — the two classes that get tablet treatment. */
    val isAtLeastMedium: Boolean get() = this != Compact
}

/**
 * The current window width class.
 *
 * Defaults to [WindowWidthClass.Compact] so a composable rendered outside [ProvideWindowSize] —
 * a `@Preview`, mostly — gets the phone layout rather than a tablet one it has no room for.
 */
val LocalWindowWidthClass = staticCompositionLocalOf { WindowWidthClass.Compact }

/**
 * Publishes the window width class to the tree below.
 *
 * Provided once near the root and read through a CompositionLocal rather than threaded down as a
 * parameter: the value is needed at the leaves (a grid's column count, a settings row's ceiling)
 * far more often than in between, and every screen in Loopky would otherwise grow a parameter it
 * does nothing with but forward.
 */
@Composable
fun ProvideWindowSize(content: @Composable () -> Unit) {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val widthClass = when {
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) ->
            WindowWidthClass.Expanded
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
            WindowWidthClass.Medium
        else -> WindowWidthClass.Compact
    }
    CompositionLocalProvider(
        LocalWindowWidthClass provides widthClass,
        content = content,
    )
}

@Composable
@ReadOnlyComposable
fun windowWidthClass(): WindowWidthClass = LocalWindowWidthClass.current
