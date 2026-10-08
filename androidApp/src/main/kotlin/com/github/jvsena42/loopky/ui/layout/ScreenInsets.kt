package com.github.jvsena42.loopky.ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

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
