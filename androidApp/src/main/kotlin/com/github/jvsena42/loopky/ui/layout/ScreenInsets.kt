package com.github.jvsena42.loopky.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * What a screen that pads itself keeps clear of: the system bars **and the display cutout**.
 *
 * `WindowInsets.systemBars` alone is right only in portrait. Rotated, the cutout moves to a side
 * edge and three-button navigation to the other, and a window targeting SDK 35+ is laid out under
 * both — which put the study screen's Close button behind the camera (#339). Not `safeDrawing`:
 * that includes the keyboard, and the study screen must not be padded for it.
 *
 * The tab screens use it too: `MainScreen` consumes whichever edges its rail or tab bar already
 * cover, so what is left is the bottom edge beside a rail and nothing under a tab bar.
 */
val WindowInsets.Companion.screenEdges: WindowInsets
    @Composable get() = systemBars.union(displayCutout)

/**
 * The part of [screenEdges] a **scrolling** screen pads its root with. The top and bottom go
 * *inside* the scroll ([screenTopBottom]) so the content passes under the transparent bars
 * instead of being clipped at them.
 */
val WindowInsets.Companion.screenSides: WindowInsets
    @Composable get() = screenEdges.only(WindowInsetsSides.Horizontal)

/** The top and bottom of [screenEdges], applied inside a scroll — after `verticalScroll`. */
val WindowInsets.Companion.screenTopBottom: WindowInsets
    @Composable get() = screenEdges.only(WindowInsetsSides.Vertical)

/** The top edge of [screenEdges]: what a header pinned above a scroll pads for. */
val WindowInsets.Companion.screenTop: WindowInsets
    @Composable get() = screenEdges.only(WindowInsetsSides.Top)

/** The bottom edge of [screenEdges]: what a scroll under a pinned header pads for. */
val WindowInsets.Companion.screenBottom: WindowInsets
    @Composable get() = screenEdges.only(WindowInsetsSides.Bottom)

/**
 * A lazy list's leading item, or the top of its first one: the top inset as a spacer. Not
 * `contentPadding`, because `asPaddingValues()` ignores consumption — an edge an ancestor already
 * padded for would be counted twice.
 */
@Composable
fun ScreenTopInsetSpacer(modifier: Modifier = Modifier) {
    Spacer(modifier.windowInsetsPadding(WindowInsets.screenTop))
}

/**
 * A lazy list's trailing item; see [ScreenTopInsetSpacer]. The tab screens' bottom edge is
 * consumed by `MainScreen`, and an `imePadding()` above the list consumes it while the keyboard
 * is up — both of which `contentPadding` would miss.
 */
@Composable
fun ScreenBottomInsetSpacer(modifier: Modifier = Modifier) {
    Spacer(modifier.windowInsetsPadding(WindowInsets.screenBottom))
}

/**
 * Keeps the status bar's icons legible over content scrolled behind it: a translucent wash of the
 * screen's own ground, heavier at the top, so the content still shows through. Draw it last, over the scrolling content,
 * in the colour that content scrolls over.
 */
@Composable
fun StatusBarScrim(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(
                Brush.verticalGradient(
                    listOf(color.copy(alpha = SCRIM_TOP_ALPHA), color.copy(alpha = SCRIM_BOTTOM_ALPHA)),
                ),
            ),
    )
}

private const val SCRIM_TOP_ALPHA = 0.9f
private const val SCRIM_BOTTOM_ALPHA = 0.7f

/** These paddings with the bottom dropped, for a pinned layout whose children pad the bottom. */
@Composable
fun PaddingValues.withoutBottom(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction),
    )
}

/** Only the start and end of these paddings; the top and bottom go into a list's contentPadding. */
@Composable
fun PaddingValues.horizontalOnly(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(start = calculateStartPadding(direction), end = calculateEndPadding(direction))
}
