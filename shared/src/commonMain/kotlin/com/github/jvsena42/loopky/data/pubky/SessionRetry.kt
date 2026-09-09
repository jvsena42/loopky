package com.github.jvsena42.loopky.data.pubky

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Whether this failure looks like a session-expired error from the homeserver. Matched defensively on
 * substrings, because the FFI's error text is not a stable API contract.
 *
 * **A transport failure is never an expiry, however it is worded.** Offline, the FFI reports
 * `"Failed to import session: Request failed: HTTP transport error…"` — which contains both "session"
 * and "import". The request never reached the homeserver, so nothing can be concluded about the
 * session; treating it as an expiry told an offline user to sign in again, and `requiresReauth` would
 * have signed them out over a dropped connection. Checked first, because the wording overlaps.
 *
 * **When the homeserver named a status, the status is the whole answer.** The FFI wraps *whatever*
 * went wrong while importing the session as `"Failed to import session: …"`, so a 429 read as an
 * expiry — and [withWriteRetry] routes an expiry into [SessionRevalidator.revalidate], itself a
 * homeserver call, which hit the same rate limit and returned terminally without ever reaching the
 * backoff branch that exists for a 429. Only `401`/`403` mean the session was refused; every other
 * status is trouble at the far end, and reading a `500` or a proxy's `502` as an expiry is not a
 * mislabelling but a loss — `signOut` revokes the session on the homeserver and clears the local key
 * with it, so a transient five-hundred permanently destroys credentials that were working.
 */
internal fun Throwable.isSessionExpired(): Boolean {
    if (this !is PubkyError) return false
    val msg = message?.lowercase() ?: return false
    if (isNetworkFailure() || isRateLimited() || isQuotaExceeded()) return false
    // The status decides it whenever the homeserver named one, in both directions. 401 and 403 are
    // the two the fork itself treats as a rejected session (`is_session_rejected`), and they are an
    // expiry even when the wording below is absent — a write that 401s after the cached session was
    // already re-imported comes back as "Failed to put …", naming no session at all.
    status?.let { return it == HTTP_UNAUTHORIZED || it == HTTP_FORBIDDEN }
    return "session" in msg &&
        ("import" in msg || "expired" in msg || "invalid" in msg)
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

/**
 * For ViewModels: true when the stored session could not be refreshed and the user has to sign in
 * again. Repos already retry once, so by the time a failure reaches a ViewModel an expiry is terminal.
 */
fun Throwable.requiresReauth(): Boolean = isSessionExpired()

/**
 * Run a session-authenticated write, retrying the three failures worth retrying.
 *
 * **Session expiry:** revalidate once and try again. The secret is read from [session] on each attempt
 * rather than captured, so the retry picks up the refreshed one.
 *
 * **Rate limiting:** back off up to [MAX_RATE_LIMIT_RETRIES] times. Measured, not speculative — a
 * homeserver returns 429 when a publish pushes several writes at once, and without this a large import
 * fails outright partway through.
 *
 * **An unreachable session round trip:** re-import once, then try again. This is #165 — every
 * authenticated write opens with `POST https://_pubky.<pubky>/session`, and when that fails it takes
 * down the whole write path while reads keep working. [SessionRevalidator.revalidate] is the one lever
 * the client has, so it is worth exactly one attempt; its own failure is **not** terminal here, unlike
 * an expiry, because the request never reached the homeserver either time. Bounded at one recovery
 * because each attempt costs a ~5s connect timeout, and a wedge surviving that needs the user — which
 * is what `ErrorReason.SessionUnreachable` is for.
 *
 * **Not** retried: a 507 out-of-storage, which is terminal until the user deletes something.
 *
 * [attempt] must re-read the session secret itself; it is invoked afresh for every try.
 */
private suspend fun withWriteRetry(
    session: SessionProvider,
    revalidator: SessionRevalidator,
    attempt: suspend (secret: String) -> Result<String>,
): Result<String> {
    var revalidated = false
    var recovered = false
    var backoff = INITIAL_BACKOFF_MS
    var rateLimitRetries = 0

    while (true) {
        val secret = session.requireSession().sessionSecret
        val result = attempt(secret)
        if (result.isSuccess) return result

        val error = result.exceptionOrNull() ?: return result

        when {
            error.isSessionExpired() && !revalidated -> {
                revalidated = true
                revalidator.revalidate().getOrElse { return Result.failure(it) }
            }

            // Never retried, and asserted here rather than relied on: 507 is terminal until the
            // user frees space, so backing off against it only delays the same failure (#91).
            error.isQuotaExceeded() -> return result

            // Deliberately below the expiry branch and above the transient ones: the wording
            // overlaps an expiry (both are "Failed to import session: …") and the cause overlaps
            // being offline, and it is neither. Re-import once, then try the write again whether
            // or not that worked — see the note above (#165).
            error.isSessionUnreachable() && !recovered -> {
                recovered = true
                revalidator.revalidate()
                delay(backoff)
            }

            error.isRateLimited() && rateLimitRetries < MAX_RATE_LIMIT_RETRIES -> {
                rateLimitRetries++
                // Jittered, because the callers that trip a 429 are the concurrent ones. Without
                // it the in-flight requests are limited at the same moment, sleep the same
                // duration, and retry in lockstep — reproducing the burst that got them limited,
                // so the whole group exhausts its budget together. Spreading the wake-ups lets
                // them drain past the limiter instead.
                delay(backoff / 2 + Random.nextLong(backoff / 2 + 1))
                backoff *= 2
            }

            else -> return result
        }
    }
}

/** Session-authenticated PUT with expiry and rate-limit retries. */
internal suspend fun PubkyClient.putWithSessionRetry(
    url: String,
    content: String,
    session: SessionProvider,
    revalidator: SessionRevalidator,
): Result<String> = withWriteRetry(session, revalidator) { secret ->
    putWithSession(url, content, secret)
}

/** Same as [putWithSessionRetry], for binary payloads. */
internal suspend fun PubkyClient.putBytesWithSessionRetry(
    url: String,
    content: ByteArray,
    session: SessionProvider,
    revalidator: SessionRevalidator,
): Result<String> = withWriteRetry(session, revalidator) { secret ->
    putBytesWithSession(url, content, secret)
}

/** Same as [putWithSessionRetry], for deletes. */
internal suspend fun PubkyClient.deleteWithSessionRetry(
    url: String,
    session: SessionProvider,
    revalidator: SessionRevalidator,
): Result<String> = withWriteRetry(session, revalidator) { secret ->
    deleteWithSession(url, secret)
}

/**
 * Enough to ride out a burst without leaving the user at a stalled progress bar.
 *
 * 8 rather than 5 because a *sweep* is not a publish: deleting a 9,000-card deck is ~90 records back to
 * back, and on device the 5-retry budget (~8s) ran out mid-sweep — the delete failed and retrying only
 * repeated it. With jitter the chain spans ~64s at worst.
 *
 * Kept at 8 after #105 halved the request count: the budget is insurance against a threshold that is
 * not ours to know, and an unused retry costs nothing where one too few is a deck that will not delete.
 */
private const val MAX_RATE_LIMIT_RETRIES = 8

private const val INITIAL_BACKOFF_MS = 250L
