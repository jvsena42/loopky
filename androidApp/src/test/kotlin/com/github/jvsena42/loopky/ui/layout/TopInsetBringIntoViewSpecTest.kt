package com.github.jvsena42.loopky.ui.layout

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A scroll that runs under the status bar must treat the strip under the bar as off screen when
 * a field is focused: the default spec saw a field there as visible and left it behind the clock
 * (#581 review). Pixels below: a 1000px container under a 100px status bar.
 */
@OptIn(ExperimentalFoundationApi::class)
class TopInsetBringIntoViewSpecTest {
    private val spec = TopInsetBringIntoViewSpec(topInsetPx = STATUS_BAR, delegate = object : BringIntoViewSpec {})

    @Test
    fun `a field partly under the status bar scrolls clear of it`() {
        // Top at 60, so 40px of it sit under the bar. The default spec would return 0 here.
        assertEquals(-40f, spec.calculateScrollDistance(offset = 60f, size = 120f, containerSize = CONTAINER))
    }

    @Test
    fun `a field wholly under the status bar scrolls to just below it`() {
        assertEquals(-90f, spec.calculateScrollDistance(offset = 10f, size = 50f, containerSize = CONTAINER))
    }

    @Test
    fun `a field already below the status bar does not move`() {
        assertEquals(0f, spec.calculateScrollDistance(offset = 100f, size = 120f, containerSize = CONTAINER))
        assertEquals(0f, spec.calculateScrollDistance(offset = 500f, size = 120f, containerSize = CONTAINER))
    }

    @Test
    fun `a field past the bottom edge scrolls up by the same amount as before`() {
        // The inset shifts the viewport's top, not its bottom: the trailing edge is still the
        // container's.
        assertEquals(70f, spec.calculateScrollDistance(offset = 950f, size = 120f, containerSize = CONTAINER))
    }

    @Test
    fun `with no status bar it is the default spec`() {
        val none = TopInsetBringIntoViewSpec(topInsetPx = 0f, delegate = object : BringIntoViewSpec {})
        assertEquals(0f, none.calculateScrollDistance(offset = 60f, size = 120f, containerSize = CONTAINER))
        assertEquals(-10f, none.calculateScrollDistance(offset = -10f, size = 120f, containerSize = CONTAINER))
    }

    private companion object {
        const val STATUS_BAR = 100f
        const val CONTAINER = 1000f
    }
}
