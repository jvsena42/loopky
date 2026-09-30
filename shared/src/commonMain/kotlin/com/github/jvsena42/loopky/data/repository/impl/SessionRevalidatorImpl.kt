package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.parseSessionPayload
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * [SessionRevalidator] backed by the Pubky FFI `revalidateSession` call.
 *
 * A [Mutex] serializes concurrent revalidation attempts, and the stale-secret check inside it
 * makes them genuinely *coalesce*: waiters that were failing on a secret which has since been
 * replaced take the refreshed session instead of issuing their own round-trip. The lock alone did
 * not do this — every waiter re-ran the FFI call in turn, which matters much more now that
 * publishing fires many writes concurrently and a expiry fails all of them at once.
 *
 * Failures coalesce too, and only for [FAILURE_WINDOW]. When the round trip is failing on
 * *transport* it does so at the connect timeout — measured at ~5s (#165) — so eight concurrent
 * writes queueing behind the lock spend forty seconds arriving at one answer that could not have
 * changed in between. The window is short on purpose: it exists to collapse one burst, never to
 * remember a verdict. A user tapping "try again" seconds later must get a real attempt, because a
 * cached "no" is indistinguishable to them from the app not trying at all.
 */
class SessionRevalidatorImpl(
    private val pubky: PubkyClient,
    private val sessionProvider: MutableSessionProvider,
    private val sessionStore: SecureSessionStore,
) : SessionRevalidator {

    private val mutex = Mutex()

    /** The last failed attempt and when it happened, for the [FAILURE_WINDOW] check. Guarded by
     * [mutex]; cleared by any success, so a recovered session never reads as a remembered one. */
    private var lastFailure: Failure? = null

    override suspend fun revalidate(): Result<Session> {
        // Captured before the lock: this is the secret the caller found wanting.
        val staleSecret = sessionProvider.current()?.sessionSecret
        return mutex.withLock {
            val now = sessionProvider.current()
            if (now != null && staleSecret != null && now.sessionSecret != staleSecret) {
                Log.d(TAG, "session already refreshed by another caller; reusing it")
                return@withLock Result.success(now)
            }
            recentFailureFor(staleSecret)?.let { error ->
                Log.d(TAG, "revalidation just failed for this secret; reusing that answer")
                return@withLock Result.failure(error)
            }
            revalidateLocked(staleSecret)
        }
    }

    /** The failure of the burst this caller is part of, if there was one moments ago. */
    private fun recentFailureFor(staleSecret: String?): Throwable? = lastFailure
        ?.takeIf { it.secret == staleSecret && it.at.elapsedNow() < FAILURE_WINDOW }
        ?.error

    private data class Failure(val secret: String?, val error: Throwable, val at: TimeMark)

    private suspend fun revalidateLocked(staleSecret: String?): Result<Session> =
        runSuspendCatching {
            val current = sessionProvider.current()
                ?: error("Cannot revalidate: no active session")
            Log.d(TAG, "revalidating session for ${current.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
            val json = pubky.revalidateSession(current.sessionSecret).getOrThrow()
            val refreshed = parseSessionPayload(json, loopkyJson)
            sessionStore.save(refreshed)
            sessionProvider.set(refreshed)
            Log.d(TAG, "session revalidated successfully")
            lastFailure = null
            refreshed
        }.onFailure {
            lastFailure = Failure(staleSecret, it, TimeSource.Monotonic.markNow())
            Log.e(TAG, "session revalidation failed: ${it.message}", it)
        }

    companion object {
        private const val TAG = "Loopky/SessionRevalidator"
        private const val PUBKY_LOG_PREFIX_LEN = 8

        /**
         * Long enough to swallow one burst of concurrent writes queued behind the lock, short
         * enough that the next thing a user does is a fresh attempt. Monotonic, so a device clock
         * that jumps mid-publish cannot widen it.
         */
        private val FAILURE_WINDOW = 3.seconds
    }
}
