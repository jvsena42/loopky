package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile

/**
 * Read-only view over the currently signed-in session. Repositories need it to author writes
 * against the homeserver without depending on [com.github.jvsena42.loopky.data.repository.IdentityRepository]
 * directly (that would be a cycle — IdentityRepository itself may end up needing repos).
 */
interface SessionProvider {
    fun current(): Session?
}

internal fun SessionProvider.requireSession(): Session =
    current() ?: error("Not signed in")

/**
 * Mutable hot cell for the active session. Hydrated at app start from [com.github.jvsena42.loopky.data.storage.SecureSessionStore]
 * and updated by [com.github.jvsena42.loopky.data.repository.impl.IdentityRepositoryImpl] on sign-in / sign-out.
 */
class MutableSessionProvider : SessionProvider {
    /**
     * `@Volatile` because this is genuinely shared mutable state: it is written by sign-in /
     * sign-out / revalidation and read by every repository coroutine on every authenticated call —
     * including the retry helpers, which re-read it precisely so a concurrent revalidation is
     * picked up. Without it, a thread could keep observing a stale secret and retry forever.
     */
    @Volatile
    private var value: Session? = null

    override fun current(): Session? = value

    /**
     * Held by revalidation and by sign-out, so neither runs inside the other. A revalidation can
     * now *mint* a session (re-signing in with a held key), and one finishing after sign-out's
     * clears would sign the user back in with a session nothing revoked. Not reentrant.
     */
    val authLock = Mutex()

    fun set(session: Session?) {
        value = session
    }
}
