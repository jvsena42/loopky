package com.github.jvsena42.loopky.util

/**
 * Desktop JVM logger.
 *
 * **Everything goes to stderr, including [d].** The desktop consumer of `:shared` is the CLI,
 * whose stdout is a machine-readable channel: an agent parses `--json` off it, and one stray log
 * line makes that output undecodable. stderr is the diagnostic channel and can be redirected
 * separately, so the split is the whole point rather than a stylistic choice.
 *
 * [debugEnabled] is off by default, matching the Android actual — `loopky --verbose` turns it on.
 */
actual object Log {
    @Volatile
    actual var debugEnabled: Boolean = false

    actual fun d(tag: String, message: String) {
        if (!debugEnabled) return
        write("D", tag, message, null)
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) = write("W", tag, message, throwable)

    actual fun e(tag: String, message: String, throwable: Throwable?) = write("E", tag, message, throwable)

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        System.err.println("$level/$tag: $message")
        // The message always, the stack only when asked. A warning or an error is worth keeping on
        // a terminal; twelve frames of coroutine machinery under it buries the one line that says
        // what went wrong, and the CLI already turns the same failure into an exit code and a
        // `--json` error object. `--verbose` is how you get the trace back.
        if (debugEnabled) throwable?.printStackTrace(System.err)
    }
}
