package com.github.jvsena42.loopky.data.storage

import java.io.InputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** What one bounded run produced. [exitCode] is [BoundedSubprocess.TIMED_OUT] or [BoundedSubprocess.NOT_RUN] when the tool never answered. */
internal data class SubprocessOutcome(val exitCode: Int, val stdout: String, val stderr: String) {
    fun describe(tool: String): String =
        "$tool exited $exitCode: ${stderr.trim().ifEmpty { "no message" }}"
}

/**
 * Run a tool under a wall-clock bound, with both pipes drained off-thread.
 *
 * **The off-thread drain is what makes the timeout real, and reading the pipes inline is the obvious
 * shape that silently disarms it.** `readText()` returns at EOF, EOF arrives when the child exits,
 * so `waitFor` is only ever reached by a process that has already finished and can never report a
 * timeout. The hang this exists for is not hypothetical: `find-generic-password` on an item whose
 * ACL is not satisfied blocks on an unlock prompt that never arrives over SSH or under `launchd`,
 * and a wedged `securityd` does the same. Unbounded, `loopky whoami` simply never returns — the
 * failure an agent can least recover from.
 *
 * Off-thread rather than `redirectOutput` to a file, because for the caller this was extracted from
 * the thing on stdout *is* the session: a temp file would put the credential on disk for the length
 * of every read, which is the posture that store exists to improve on.
 *
 * `ProcessBuilder` rather than a native call is also what keeps the image one file — it needs no
 * reachability metadata, where a new JNA surface needs registration in three files and is the exact
 * shape that has twice made the build emit a second one (Architecture.md §13.11).
 */
internal class BoundedSubprocess(
    /** Named in the failure text, so a message says which tool did not answer. */
    private val tool: String,
    private val timeoutSeconds: Long,
    /** Appended to the timeout message — the caller knows what a hang here usually means. */
    private val timeoutNote: String,
    private val drainTimeoutSeconds: Long = DRAIN_TIMEOUT_SECONDS,
) {

    fun run(command: List<String>, stdin: String? = null): SubprocessOutcome = runCatching {
        val process = ProcessBuilder(command).start()
        try {
            val out = process.inputStream.drainOffThread()
            val err = process.errorStream.drainOffThread()
            process.outputStream.use { if (stdin != null) it.write(stdin.toByteArray()) }
            if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // Null rather than "" when a drain somehow outlives the process it was reading: an
                // empty stdout is a *valid* answer shape for these callers, and letting a truncated
                // read pass as one turns a lost byte into "there is no session".
                val text = out.text()
                if (text == null) {
                    SubprocessOutcome(NOT_RUN, "", "$tool exited but its output never arrived")
                } else {
                    SubprocessOutcome(process.exitValue(), text, err.text().orEmpty())
                }
            } else {
                SubprocessOutcome(TIMED_OUT, "", "no answer in ${timeoutSeconds}s — $timeoutNote")
            }
        } finally {
            // A no-op once it has exited, and the only thing that unblocks the drain threads on the
            // timeout path — or after a failed write to its stdin, which used to leave it running.
            process.destroyForcibly()
        }
    }.getOrElse { SubprocessOutcome(NOT_RUN, "", it.message ?: it::class.simpleName.orEmpty()) }

    private fun InputStream.drainOffThread(): FutureTask<String> {
        val task = FutureTask { runCatching { bufferedReader().use { it.readText() } }.getOrDefault("") }
        Thread(task, "loopky-subprocess-drain").apply { isDaemon = true }.start()
        return task
    }

    /** The drained text, or null if it never arrived — the caller must not read that as empty. */
    private fun FutureTask<String>.text(): String? =
        runCatching { get(drainTimeoutSeconds, TimeUnit.SECONDS) }.getOrNull()

    internal companion object {
        /** The tool was still running when the bound expired. */
        const val TIMED_OUT = -1

        /** The tool never ran, or its output never arrived. */
        const val NOT_RUN = -2
        const val DRAIN_TIMEOUT_SECONDS = 2L
    }
}
