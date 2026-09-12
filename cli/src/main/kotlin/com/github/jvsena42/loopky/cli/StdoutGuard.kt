package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.util.Log
import com.sun.jna.Function
import com.sun.jna.Platform
import com.sun.jna.Pointer
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
 * **Windows needs a different call, and not merely a different spelling** (#301). `msvcrt` does
 * export `dup`/`dup2`/`write`, so this resolved there and reported success — against msvcrt's own
 * private CRT descriptor table, which nothing in this process consults. The JVM writes to a handle
 * it captured at start-up and Rust calls `GetStdHandle` on every write, so the swap moved neither,
 * while `System.out` was replaced by a stream writing into that table. It succeeded and lied, which
 * is worse than failing. [Win32Stdio] uses `SetStdHandle` instead, which is what Rust actually
 * re-reads, and leaves `System.out` on the handle it already holds.
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
    //
    // Skipped on Windows: `System.out` is already writing to the handle `dup` just handed back, so
    // re-wrapping it would add a second path to the same console for nothing. Only the *standard
    // handle* moved there, which is what the Rust layer re-reads.
    if (libc.writesThroughSavedDescriptor) {
        install(PrintStream(FdOutputStream(libc, realStdout), true, Charsets.UTF_8.name()))
    }
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

    /**
     * Whether the saved descriptor from [dup] is one this process can write through.
     *
     * False on Windows, where the "descriptor" is a handle the JVM's own `System.out` already
     * holds: there the swap is done by pointing the *standard handle* elsewhere and leaving Java's
     * stream alone, so wrapping it in an [FdOutputStream] would be writing to the console through a
     * second, unnecessary path.
     */
    val writesThroughSavedDescriptor: Boolean get() = true
}

/** libc on a POSIX host, kernel32 on Windows. */
internal fun defaultStdio(osName: String = System.getProperty("os.name").orEmpty()): Stdio =
    if (isWindowsOs(osName)) Win32Stdio else JnaStdio

internal fun isWindowsOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.startsWith("Windows", ignoreCase = true)

/**
 * The same swap through kernel32, which is the only thing the Rust layer will notice (#301).
 *
 * `SetStdHandle(STD_OUTPUT_HANDLE, …)` rather than a descriptor dance: Rust's `std::io::stdout()`
 * calls `GetStdHandle` on **every** write, so re-pointing that one slot moves everything it prints,
 * exactly as `dup2` does on POSIX. The JVM is unaffected because it kept the handle it captured at
 * start-up, which is why [writesThroughSavedDescriptor] is false and `System.out` is left alone.
 *
 * [Function.getFunction] for the same reason as everywhere else here: a mapped `Library` interface
 * would be a dynamic proxy `native-image` has to be told about, and handles are pointer-sized, so
 * they are carried as [Pointer] rather than `Int`.
 */
private object Win32Stdio : Stdio {

    override val writesThroughSavedDescriptor = false

    override fun dup(fd: Int): Int = if (handleFor(fd) == null) -1 else fd

    override fun dup2(from: Int, to: Int): Int {
        val source = handleFor(from) ?: return -1
        val ok = kernel32("SetStdHandle").invokeInt(arrayOf<Any>(stdHandleId(to), source))
        return if (ok == 0) -1 else to
    }

    /** Unreachable: [writesThroughSavedDescriptor] is false, so nothing writes through this. */
    override fun write(fd: Int, bytes: ByteArray, length: Int): Int = -1

    private fun handleFor(fd: Int): Pointer? =
        kernel32("GetStdHandle").invokePointer(arrayOf<Any>(stdHandleId(fd)))
            ?.takeIf { Pointer.nativeValue(it) != INVALID_HANDLE }

    private fun stdHandleId(fd: Int): Int = if (fd == STDERR_FD) STD_ERROR_HANDLE else STD_OUTPUT_HANDLE

    private fun kernel32(symbol: String): Function = Function.getFunction("kernel32", symbol)

    private const val STD_OUTPUT_HANDLE = -11
    private const val STD_ERROR_HANDLE = -12

    /** `INVALID_HANDLE_VALUE`, which `GetStdHandle` returns rather than null on failure. */
    private const val INVALID_HANDLE = -1L
}

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
