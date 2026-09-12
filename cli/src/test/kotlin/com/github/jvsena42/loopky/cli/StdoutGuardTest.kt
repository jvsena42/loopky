package com.github.jvsena42.loopky.cli

import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The descriptor swap, driven against a fake libc.
 *
 * Deliberately never installed into `System.out` here: a real `dup2(2, 1)` inside the test JVM
 * would send Gradle's own stdout to stderr for the rest of the run. What is worth pinning is the
 * *order* — the real stdout has to be duplicated **before** fd 1 is overwritten, or the swap
 * hands back a second handle on stderr and the envelope disappears into it — and that failure is
 * invisible to a build.
 */
class StdoutGuardTest {

    @Test
    fun `duplicates the real stdout before pointing fd 1 at stderr`() {
        val libc = FakeStdio()
        var installed: PrintStream? = null

        assertTrue(reserveStdoutForResults(libc) { installed = it })

        assertEquals(listOf("dup(1)", "dup2(2,1)"), libc.calls)
        installed?.print("hello")
        assertEquals("hello", libc.written(fd = SAVED_FD))
    }

    /**
     * `print` with an explicit `\n`, not `println` (#301). `println` emits the *platform* line
     * separator, so this asserted `\r\n` against `\n` on Windows and failed for a reason that has
     * nothing to do with the descriptor swap being tested — the envelope is what matters here, not
     * how the host spells the end of a line.
     */
    @Test
    fun `writes the result to the saved descriptor and not to fd 1`() {
        val libc = FakeStdio()
        var installed: PrintStream? = null
        reserveStdoutForResults(libc) { installed = it }

        installed?.print("""{"ok":true}""" + "\n")

        assertEquals("""{"ok":true}""" + "\n", libc.written(fd = SAVED_FD))
        assertEquals("", libc.written(fd = 1))
    }

    /** A short write is what a pipe does under load, not a failure — the rest has to follow it. */
    @Test
    fun `finishes a write the descriptor only partly took`() {
        val libc = FakeStdio(maxWrite = 3)
        var installed: PrintStream? = null
        reserveStdoutForResults(libc) { installed = it }

        installed?.print("abcdefgh")

        assertEquals("abcdefgh", libc.written(fd = SAVED_FD))
    }

    /** A host where the symbols will not resolve keeps the descriptors it had, and says so. */
    @Test
    fun `reports failure rather than throwing when libc is unavailable`() {
        var installed = false
        val result = reserveStdoutForResults(ThrowingStdio) { installed = true }

        assertFalse(result)
        assertFalse(installed, "nothing may be installed over System.out when the swap failed")
    }

    @Test
    fun `installs nothing when the descriptor cannot be duplicated`() {
        var installed = false
        val result = reserveStdoutForResults(FakeStdio(dupResult = -1)) { installed = true }

        assertFalse(result)
        assertFalse(installed)
    }

    private class FakeStdio(
        private val dupResult: Int = SAVED_FD,
        private val maxWrite: Int = Int.MAX_VALUE,
    ) : Stdio {
        val calls = mutableListOf<String>()
        private val sinks = mutableMapOf<Int, StringBuilder>()

        override fun dup(fd: Int): Int {
            calls += "dup($fd)"
            return dupResult
        }

        override fun dup2(from: Int, to: Int): Int {
            calls += "dup2($from,$to)"
            return to
        }

        override fun write(fd: Int, bytes: ByteArray, length: Int): Int {
            val taken = minOf(length, maxWrite)
            sinks.getOrPut(fd) { StringBuilder() }
                .append(String(bytes, 0, taken, Charsets.UTF_8))
            return taken
        }

        fun written(fd: Int): String = sinks[fd]?.toString().orEmpty()
    }

    private object ThrowingStdio : Stdio {
        override fun dup(fd: Int): Int = throw UnsatisfiedLinkError("no dup here")
        override fun dup2(from: Int, to: Int): Int = throw UnsatisfiedLinkError("no dup2 here")
        override fun write(fd: Int, bytes: ByteArray, length: Int): Int = throw UnsatisfiedLinkError("no write here")
    }

    private companion object {
        /** What the fake's `dup` hands back — any descriptor that is not 1 or 2. */
        const val SAVED_FD = 7
    }
}
