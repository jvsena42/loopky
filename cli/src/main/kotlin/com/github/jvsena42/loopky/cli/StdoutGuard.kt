package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.util.Log
import com.sun.jna.Function
import com.sun.jna.Platform
import java.io.OutputStream
import java.io.PrintStream

/**
 * Keep stdout for the result, whatever the layers underneath think.
 *
 * `--json` is a versioned API an agent parses (`SCHEMA_VERSION`), which only means anything if the
 * envelope is the sole thing on stdout. It was not: `libpubkycore` installs a `tracing` subscriber
 * whose default writer is **stdout**, so a DHT bootstrap failure — routine on a box that reaches
 * the homeserver fine over HTTPS — lands ahead of the envelope and `jq` and `json.load()` both
 * fail on it (#229). `2>/dev/null` does not help, and neither does `RUST_LOG`: quieting the
 * subscriber lowers the *odds* of a line rather than the guarantee, and an error is exactly the
 * thing that should still be reported.
 *
 * So the fix is a file descriptor, not a log level. `dup2(2, 1)` points **fd 1 itself** at stderr,
 * which catches everything writing to the raw descriptor — the Rust layer, anything it links, and
 * any future dependency that prints — and Kotlin's `System.out` is re-pointed at a `dup` of the
 * real stdout taken first. Nothing above has to cooperate, and there is nothing left to
 * accidentally leave on the wrong stream.
 *
 * [defaultRustLogToWarn] stays as it is and is not made redundant by this: it decides how much the
 * SDK says, this decides where it says it.
 *
 * Best-effort in that it never throws, but **the answer is load-bearing**: a host where this could
 * not be done keeps the descriptors it had, and [startCli] refuses `--json` rather than emitting a
 * channel it cannot vouch for. Noisy stdout is not a reason to refuse to *run*; it is a reason not
 * to promise a machine-readable one.
 */
internal fun reserveStdoutForResults(
    libc: Stdio = defaultStdio(),
    install: (PrintStream) -> Unit = System::setOut,
): Boolean = runCatching {
    // Before the descriptors move: whatever is already buffered belongs on the real stdout, and
    // after the swap fd 1 is stderr.
    System.out.flush()

    val realStdout = libc.dup(STDOUT_FD)
    if (realStdout < 0) return@runCatching false
    if (libc.dup2(STDERR_FD, STDOUT_FD) < 0) return@runCatching false

    // autoFlush, because `exitProcess` runs no flush of its own and every result here is one
    // `println`. A buffered envelope that never reaches the pipe is the failure this exists to
    // prevent, one layer along.
    install(PrintStream(FdOutputStream(libc, realStdout), true, Charsets.UTF_8.name()))
    true
}.getOrElse {
    Log.d(TAG, "could not reserve stdout: ${it.message}")
    false
}

/** The three libc calls this needs, as an interface so the swap is testable without moving fd 1. */
internal interface Stdio {
    fun dup(fd: Int): Int
    fun dup2(from: Int, to: Int): Int

    /** `write(2)`: the number of bytes taken, which may be fewer than [length]. */
    fun write(fd: Int, bytes: ByteArray, length: Int): Int
}

internal fun defaultStdio(): Stdio = JnaStdio

/**
 * libc through JNA, which is already linked for the FFI.
 *
 * [Function.getFunction] rather than a mapped `Library` interface for the same reason
 * [defaultRustLogToWarn] uses it: an interface is a dynamic proxy `native-image` has to be told
 * about, for three calls.
 */
private object JnaStdio : Stdio {
    override fun dup(fd: Int): Int = call("dup", arrayOf<Any>(fd))
    override fun dup2(from: Int, to: Int): Int = call("dup2", arrayOf<Any>(from, to))
    override fun write(fd: Int, bytes: ByteArray, length: Int): Int =
        call("write", arrayOf<Any>(fd, bytes, length))

    private fun call(symbol: String, arguments: Array<Any>): Int =
        Function.getFunction(Platform.C_LIBRARY_NAME, symbol).invokeInt(arguments)
}

/**
 * An [OutputStream] over a raw descriptor, so the real stdout can be written to without a
 * [java.io.FileDescriptor] — which has no public constructor taking an int, and whose private field
 * would be one more reflection entry in the hand-curated `native-image` metadata.
 */
private class FdOutputStream(private val libc: Stdio, private val fd: Int) : OutputStream() {

    override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        // A short write is normal on a pipe, not an error: `write(2)` returns what it took.
        var remaining = if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length)
        while (remaining.isNotEmpty()) {
            val taken = libc.write(fd, remaining, remaining.size)
            if (taken <= 0) return
            remaining = remaining.copyOfRange(taken, remaining.size)
        }
    }
}

private const val STDOUT_FD = 1
private const val STDERR_FD = 2

private const val TAG = "Loopky/Cli"
