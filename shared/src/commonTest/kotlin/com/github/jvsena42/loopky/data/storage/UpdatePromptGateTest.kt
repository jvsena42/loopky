package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.testing.FakeAppPreferences
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatePromptGateTest {

    private var day = 100
    private val preferences = FakeAppPreferences()
    private val gate = UpdatePromptGate(preferences) { day }

    @Test
    fun opensOnceOnTheSameDay() = runTest {
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }

    @Test
    fun opensAgainTheNextDay() = runTest {
        assertTrue(gate.tryAcquire())
        day = 101
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun staysShutAcrossARestartOnTheSameDay() = runTest {
        assertTrue(gate.tryAcquire())
        assertFalse(UpdatePromptGate(preferences) { day }.tryAcquire())
    }
}
