package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenSpeedTest {
    @Test
    fun `normal speed keeps the old 1 then 0_75 then 0_5 steps`() {
        assertEquals(listOf(1f, 0.75f, 0.5f, 0.5f), (0..3).map { ListenSpeed.forRepeat(1f, it) })
    }

    @Test
    fun `a slower start steps down from itself and stops at the floor`() {
        assertEquals(listOf(0.65f, 0.5f, 0.5f), (0..2).map { ListenSpeed.forRepeat(0.65f, it) })
        assertEquals(0.5f, ListenSpeed.forRepeat(0.5f, 0))
    }

    @Test
    fun `stored values are clamped and snapped to a slider stop`() {
        assertEquals(0.5f, ListenSpeed.sanitized(0.1f))
        assertEquals(1f, ListenSpeed.sanitized(2f))
        assertEquals(0.65f, ListenSpeed.sanitized(0.6512f))
        assertEquals(0.7f, ListenSpeed.sanitized(0.68f))
        assertEquals(1f, ListenSpeed.sanitized(Float.NaN))
    }

    @Test
    fun `the slider has a stop every 0_05 between the ends`() {
        assertEquals(9, ListenSpeed.SLIDER_STEPS)
    }

    @Test
    fun `counting repeats stops once the floor is reached`() {
        assertTrue(ListenSpeed.canSlowFurther(1f, 1))
        assertFalse(ListenSpeed.canSlowFurther(1f, 2))
        assertFalse(ListenSpeed.canSlowFurther(0.5f, 0))
    }
}
