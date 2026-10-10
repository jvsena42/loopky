package com.github.jvsena42.loopky.cli

/**
 * Progress under `--json`: at most one line every [intervalMillis], and none for a command that
 * finishes inside the first interval.
 *
 * The counter stream is suppressed in that mode because the result carries the same numbers, but
 * suppressing all of it left a 270-row `card edit --from-file` and a whole-deck `card reorder`
 * silent for five minutes, and an agent watching that cannot tell slow from stuck (#480). A line
 * every so often answers that without putting a 20,000-card import's counter on stderr.
 *
 * **Driven by progress, never by a timer.** A line is printed only when a caller reports one, so
 * each is evidence that something completed; a timer would keep printing through a real hang. The
 * cost is that a single slow request, or a phase that reports nothing, is a gap.
 */
internal class ProgressHeartbeat(
    private val sink: (String) -> Unit,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastReport = now()

    /** Chunk writes report from several coroutines at once, hence the lock. */
    @Synchronized
    fun report(line: String) {
        val time = now()
        if (time - lastReport < intervalMillis) return
        lastReport = time
        sink("loopky: still working - $line")
    }

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 15_000L
    }
}
