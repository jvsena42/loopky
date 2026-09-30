package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.completeWithin
import com.github.jvsena42.loopky.data.repository.AuthFlowHandle
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `login` had no way to be bounded, so an unattended caller's only tool was SIGKILL — which skips
 * the shutdown hook that deletes a `--qr-out` file, leaving a live auth URL on disk (#240).
 *
 * The test that matters is the wall clock. The obvious implementation — `withTimeout { complete() }`
 * — **does not work**: `complete()` blocks in the FFI on a `Dispatchers.IO` thread, and
 * `withContext` does not return until its body does, so a 300 ms timeout around a 5 s call was
 * measured returning after 5 s. Without this assertion that regression is invisible: the exit code
 * is right and only the timing is wrong.
 */
class LoginTimeoutTest {

    /** A handle that behaves like the real one: a blocking native call nothing can interrupt. */
    private class NeverApproved(private val blockMillis: Long = 30_000) : AuthFlowHandle {
        override val authUrl = "pubkyauth:///?relay=x&caps=/pub/loopky:rw"
        override suspend fun complete(): Result<Session> = withContext(Dispatchers.IO) {
            Thread.sleep(blockMillis)
            error("should never be reached")
        }
    }

    @Test
    fun `gives up on time rather than waiting for the blocking call`() = runBlocking {
        val start = System.currentTimeMillis()
        val error = assertFailsWith<CliError> { NeverApproved().completeWithin(seconds = 1) }
        val elapsed = System.currentTimeMillis() - start
        assertEquals(ExitCode.Timeout, error.exitCode)
        assertTrue(elapsed < 10_000, "waited ${elapsed}ms for a 1s timeout")
    }

    /** Nothing was signed in, and the code on screen is spent — the message has to say both. */
    @Test
    fun `says what happened and what to do`() = runBlocking {
        val error = assertFailsWith<CliError> { NeverApproved().completeWithin(seconds = 1) }
        val message = error.message.orEmpty()
        assertTrue("this process is not signed in" in message, message)
        // Never the stronger claim: the await is unobserved rather than stopped, so an approval
        // landing as the process exits still persists a session.
        assertTrue("Nothing was stored" !in message, message)
        assertTrue("loopky whoami" in message, message)
        assertTrue("loopky login" in message, message)
    }

    /**
     * The deferred has to be completed on every path out of that thread.
     *
     * Without the `catch`, anything thrown out of `runBlocking` killed the thread with the deferred
     * never completed — so the caller waited the *whole* `--timeout` and then reported `timeout`
     * for what was an FFI failure. A wrong diagnosis after a long wait is worse than either alone.
     * `Error` rather than `Exception` because `UnsatisfiedLinkError` is the realistic one, and it
     * is not what the `runSuspendCatching` inside `complete()` is protecting against.
     */
    @Test
    fun `a throw out of the awaiting thread is reported as itself, not as a timeout`() = runBlocking {
        val handle = object : AuthFlowHandle {
            override val authUrl = "pubkyauth:///"
            override suspend fun complete(): Result<Session> = throw UnsatisfiedLinkError("pubkycore")
        }

        val start = System.currentTimeMillis()
        val outcome = handle.completeWithin(seconds = 30)
        val elapsed = System.currentTimeMillis() - start

        assertIs<UnsatisfiedLinkError>(outcome.exceptionOrNull())
        assertTrue(elapsed < 10_000, "waited ${elapsed}ms for a failure that was immediate")
    }

    /** No `--timeout` still means wait forever, which is right for a human at a terminal. */
    @Test
    fun `without a timeout the handle is awaited as it always was`() = runBlocking {
        val handle = object : AuthFlowHandle {
            override val authUrl = "pubkyauth:///"
            override suspend fun complete(): Result<Session> = Result.failure(IllegalStateException("relay died"))
        }
        assertEquals("relay died", handle.completeWithin(seconds = null).exceptionOrNull()?.message)
    }

    @Test
    fun `a timeout that is not a positive number is a usage error`() {
        for (bad in listOf("twenty", "0", "-5")) {
            val error = assertFailsWith<CliError> {
                Args.parse(arrayOf("login", "--timeout", bad)).positiveIntOrNull("timeout")
            }
            assertEquals(ExitCode.Usage, error.exitCode, "for --timeout $bad")
        }
    }
}
