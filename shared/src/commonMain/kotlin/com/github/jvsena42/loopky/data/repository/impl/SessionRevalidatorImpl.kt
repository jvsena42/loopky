package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.isSessionExpired
import com.github.jvsena42.loopky.data.pubky.parseSessionPayload
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * [SessionRevalidator] backed by the Pubky FFI `revalidateSession` call.
 *
 * [MutableSessionProvider.authLock] serializes concurrent revalidation attempts, and the stale-secret check inside it
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
 *
 * **A rejected session on a key Loopky holds and nobody has backed up is replaced by signing in
 * again with that key.** That user has no other way back in — no Ring, no phrase written down —
 * so a homeserver that stops honouring the session (cookie sessions are deprecated upstream) would
 * otherwise lock them out with the key sitting in the keystore.
 * Only a rejection triggers it — a network failure says nothing about the session — and only for
 * the signed-in pubky's own registered key. A backed-up key is left alone; its owner can restore.
 * And never into a wider scope than the session had: the CLI's `/pub/loopky/:rw` is structural.
 */
internal class SessionRevalidatorImpl(
    private val pubky: PubkyClient,
    private val sessionProvider: MutableSessionProvider,
    private val sessionStore: SecureSessionStore,
    private val localKeyStore: LocalKeyStore,
) : SessionRevalidator {

    /** The last failed attempt and when it happened, for the [FAILURE_WINDOW] check. Guarded by
     * the auth lock; cleared by any success, so a recovered session never reads as a remembered one. */
    private var lastFailure: Failure? = null

    override suspend fun revalidate(): Result<Session> {
        // Captured before the lock: this is the secret the caller found wanting.
        val staleSecret = sessionProvider.current()?.sessionSecret
        return sessionProvider.authLock.withLock {
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
            val json = pubky.revalidateSession(current.sessionSecret)
                .recoverRejection(current)
                .getOrThrow()
            // Neither payload carries the homeserver or the profile, and neither moved.
            val refreshed = parseSessionPayload(json, loopkyJson).let {
                it.copy(
                    identity = current.identity,
                    homeserver = it.homeserver.ifBlank { current.homeserver },
                )
            }
            sessionStore.save(refreshed)
            sessionProvider.set(refreshed)
            Log.d(TAG, "session revalidated successfully")
            lastFailure = null
            refreshed
        }.onFailure {
            lastFailure = Failure(staleSecret, it, TimeSource.Monotonic.markNow())
            Log.e(TAG, "session revalidation failed: ${it.message}", it)
        }

    private suspend fun Result<String>.recoverRejection(current: Session): Result<String> {
        val rejection = exceptionOrNull()?.takeIf { it.isSessionExpired() } ?: return this
        // A vault that will not decrypt (a restored backup, a reset keystore) must not replace the
        // expiry with its own error: the caller still has to be told to sign in.
        val held = runSuspendCatching { localKeyStore.current() }.getOrNull()
            ?.takeIf { it.registered && it.backedUpBy.isEmpty() && it.pubky == current.identity.pubky }
            ?: return this
        Log.d(TAG, "session rejected; signing in again with the un-backed-up key Loopky holds")
        // The class only: the FFI's message for a call handed a secret key is not loggable. Either
        // way the rejection stays the answer, rather than the second call's error.
        val json = pubky.signIn(held.secretKeyHex)
            .onFailure { Log.e(TAG, "re-sign-in with the held key failed — ${it::class.simpleName}") }
            .getOrNull() ?: return Result.failure(rejection)
        val minted = runCatching { parseSessionPayload(json, loopkyJson) }.getOrNull()
        val granted = minted?.capabilities?.map { it.value }?.toSet()
        if (granted == null || !current.capabilities.map { it.value }.containsAll(granted)) {
            Log.e(TAG, "re-sign-in would widen the session's scope; revoking it")
            minted?.let { pubky.signOut(it.sessionSecret) }
            return Result.failure(rejection)
        }
        return Result.success(json)
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
