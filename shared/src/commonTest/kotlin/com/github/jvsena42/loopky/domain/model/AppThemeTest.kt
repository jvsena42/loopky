package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppThemeTest {

    @Test
    fun `a stored name round-trips`() {
        AppTheme.entries.forEach { theme ->
            assertEquals(theme, AppTheme.fromNameOrSystem(theme.name))
        }
    }

    @Test
    fun `a name a newer release wrote falls back to System`() {
        assertEquals(AppTheme.System, AppTheme.fromNameOrSystem("HighContrast"))
    }

    @Test
    fun `a blank preference falls back to System`() {
        assertEquals(AppTheme.System, AppTheme.fromNameOrSystem(""))
    }
}

class DayNightScheduleTest {
    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun `daytime is not night`() {
        listOf(at(6), at(9, 30), at(12), at(19, 59)).forEach {
            assertFalse(DayNightSchedule.isNightAt(it), "minute $it")
        }
    }

    @Test
    fun `the window wraps midnight`() {
        listOf(at(20), at(23, 59), at(0), at(3), at(5, 59)).forEach {
            assertTrue(DayNightSchedule.isNightAt(it), "minute $it")
        }
    }

    @Test
    fun `the boundaries belong to the side that starts`() {
        assertTrue(DayNightSchedule.isNightAt(at(DayNightSchedule.darkFromHour)))
        assertFalse(DayNightSchedule.isNightAt(at(DayNightSchedule.darkUntilHour)))
    }

    /** A caller loops on this; a zero at the boundary would spin. */
    @Test
    fun `the wait until the next flip is never zero`() {
        assertTrue(DayNightSchedule.millisUntilFlip() > 0)
    }

    /**
     * The shipped window wraps midnight. This is not an assertion about the constants for its own
     * sake — moving them to a daytime window is the obvious way to see the feature work on a
     * device, and it is what caught the `or` answering "night" for every minute of the day.
     */
    @Test
    fun `a window that does not wrap midnight is still a window`() {
        val evening = DayNightSchedule.darkFromHour > DayNightSchedule.darkUntilHour
        assertTrue(evening, "the shipped window is expected to wrap midnight")
        // The other order has to hold too, since it is one edit away.
        assertFalse(DayNightSchedule.isNightAt(at(12)))
    }
}
