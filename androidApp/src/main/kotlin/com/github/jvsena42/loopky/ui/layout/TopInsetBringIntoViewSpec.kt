package com.github.jvsena42.loopky.ui.layout

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity

/**
 * A [BringIntoViewSpec] for a scroll that runs under the status bar: the strip it covers counts
 * as outside the viewport.
 *
 * The default spec scrolls only when the target is outside the container, and a container that
 * starts at y=0 holds a field sitting under the clock — so focusing it scrolled nothing and the
 * reader typed into a field behind the bar (#581 review).
 */
@OptIn(ExperimentalFoundationApi::class)
class TopInsetBringIntoViewSpec(
    private val topInsetPx: Float,
    private val delegate: BringIntoViewSpec,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        delegate.calculateScrollDistance(
            offset = offset - topInsetPx,
            size = size,
            containerSize = containerSize - topInsetPx,
        )
}

/**
 * Wraps a screen whose scroll runs under the status bar (see `screenTopBottom`) and holds
 * focusable fields, so focusing one brings it clear of the bar rather than merely on screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BringIntoViewClearOfStatusBar(content: @Composable () -> Unit) {
    val topInset = WindowInsets.screenTop.getTop(LocalDensity.current).toFloat()
    val parent = LocalBringIntoViewSpec.current
    val spec = remember(topInset, parent) { TopInsetBringIntoViewSpec(topInset, parent) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
