package com.github.jvsena42.loopky.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ExpirablePassTest {

    private class Recorder {
        val completions = mutableListOf<Boolean>()
        var reschedules = 0
    }

    private fun pass(scope: CoroutineScope, recorder: Recorder, work: suspend () -> Boolean) =
        ExpirablePass(
            scope = scope,
            reschedule = { recorder.reschedules++ },
            complete = { recorder.completions += it },
            work = work,
        )

    @Test
    fun finishedPassCompletesOnceAndReschedulesOnlyWhenAsked() = runTest {
        val done = Recorder()
        pass(this, done) { false }.start()
        val again = Recorder()
        pass(this, again) { true }.start()
        runCurrent()

        assertEquals(listOf(true), done.completions)
        assertEquals(0, done.reschedules)
        assertEquals(listOf(true), again.completions)
        assertEquals(1, again.reschedules)
    }

    @Test
    fun expiryMidPassCompletesOnceAsFailedAndCancelsTheWork() = runTest {
        val recorder = Recorder()
        val blocker = CompletableDeferred<Boolean>()
        var workReturned = false
        val pass = pass(this, recorder) { blocker.await().also { workReturned = true } }
        pass.start()
        runCurrent()

        pass.expire()
        blocker.complete(false)
        runCurrent()

        assertEquals(listOf(false), recorder.completions)
        assertEquals(1, recorder.reschedules)
        assertFalse(workReturned)
    }

    @Test
    fun expiryAfterThePassFinishedDoesNothing() = runTest {
        val recorder = Recorder()
        val pass = pass(this, recorder) { false }
        pass.start()
        runCurrent()

        pass.expire()

        assertEquals(listOf(true), recorder.completions)
        assertEquals(0, recorder.reschedules)
    }

    @Test
    fun workDoesNotStartBeforeStart() = runTest {
        val recorder = Recorder()
        var ran = false
        pass(backgroundScope, recorder) {
            ran = true
            false
        }
        runCurrent()

        assertFalse(ran)
        assertEquals(emptyList(), recorder.completions)
    }
}
