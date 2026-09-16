package com.github.jvsena42.loopky.data.nexus

import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Retry an indexer read that failed for a reason another attempt could fix.
 *
 * Discover is one screen built from six or seven indexer reads, so a single dropped request is the
 * difference between a full screen and an error block — and a public REST service behind a CDN drops
 * one occasionally. Retrying is cheap here in a way it is not for a write: these are unauthenticated
 * `GET`s with no side effect, so a duplicate costs a round-trip and nothing else.
 *
 * **What is deliberately *not* retried is the point of this function.**
 *
 * - **Offline is terminal.** A device with no route, or one that cannot resolve the host, will
 *   answer the same way in 200ms and in 2s; backing off against it only makes the user wait longer
 *   for the error block they were always going to get — and it delays the one screen that could
 *   have told them to check their connection. This is the same rule §8.5 applies to a 507: a
 *   backoff chain against a permanent condition never converges.
 * - **4xx is terminal**, apart from 429. A 404 is an indexer that predates the endpoint (#134) and
 *   a 400 is a malformed query; both are facts about the request, and asking three times does not
 *   change the request.
 * - **5xx and 429 are retried**, jittered. Jitter matters because the callers that trip a 429 are
 *   the concurrent ones: without it the in-flight reads are limited at the same moment, sleep the
 *   same duration and retry in lockstep, reproducing the burst that got them limited.
 *
 * Cancellation passes through — [runSuspendCatching], never plain `runCatching`.
 */
internal suspend fun <T> withIndexerRetry(
    what: String,
    read: suspend () -> T,
): T {
    var backoff = INITIAL_BACKOFF_MS
    var attempts = 0

    while (true) {
        val result = runSuspendCatching { read() }
        result.getOrNull()?.let { return it }

        val error = result.exceptionOrNull() ?: return result.getOrThrow()
        attempts++
        if (attempts > MAX_RETRIES || !error.isWorthRetrying()) throw error

        Log.d(TAG, "$what: retry $attempts of $MAX_RETRIES in ${backoff}ms — ${error.message}")
        delay(backoff / 2 + Random.nextLong(backoff / 2 + 1))
        backoff *= 2
    }
}

/**
 * Whether another attempt could plausibly answer differently.
 *
 * A transport failure is read as offline and refused: `HttpFetcher` only fails a `send` for "no
 * connectivity, DNS, TLS or timeout", and every one of those is a condition a 250ms wait does not
 * change. A *timeout* is the arguable case and is refused with the rest — the read already waited
 * out `DEFAULT_TIMEOUT_MS`, and spending another 15s of a reader's time on the same host is worse
 * than telling them now.
 */
private fun Throwable.isWorthRetrying(): Boolean {
    val status = (this as? HttpError)?.statusCode ?: return false
    return status == TOO_MANY_REQUESTS || status in SERVER_ERROR_RANGE
}

private const val TAG = "Loopky/NexusRetry"
private const val MAX_RETRIES = 2
private const val INITIAL_BACKOFF_MS = 250L
private const val TOO_MANY_REQUESTS = 429
private val SERVER_ERROR_RANGE = 500..599
