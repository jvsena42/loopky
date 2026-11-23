package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.util.epochMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Which [SocialLayout] each user's social records are in (#616).
 *
 * **The homeserver decides, not Nexus.** Nexus flips a user to `v1` when it handles their
 * `/pub/social/v1/profile.json` PUT, and it handles one homeserver's events in order. So once that
 * file exists, a later `pubky.app` write reaches Nexus after the flip and is ignored, even while
 * Nexus's own `epoch` still reads `v0`. Reading the file is the only answer that is never behind.
 *
 * [SocialEpoch.V1] is kept for good, since nothing moves a user back. [SocialEpoch.V0] is asked
 * again after [V0_TTL_MILLIS], because pubky.app can migrate the user from another device while
 * Loopky runs. A read that fails for any reason but a 404 throws: a write routed on a guess would
 * land where Nexus ignores it.
 *
 * **A write runs inside [writing], not after [layoutOf].** Loopky's own migration copies the
 * `pubky.app` tree and then flips; a write that picked its layout before the copy and landed after
 * it would be lost to the flip, or an unfollow undone by it. [writing] holds the layout until the
 * write is done, and [exclusive] waits for every such write and keeps new ones out. Both are
 * reentrant within one coroutine, so a write inside a write, or inside [exclusive], goes through;
 * [exclusive] inside [writing] would wait for itself, so it throws. Reads ([of]) never wait.
 */
class SocialEpochs(
    private val pubky: PubkyClient,
    private val now: () -> Long = ::epochMillis,
) {
    private val known = mutableMapOf<String, Pair<SocialEpoch, Long>>()
    private val gates = mutableMapOf<String, Gate>()
    private val retired = mutableSetOf<String>()
    private val lock = Mutex()

    /** Per user: writes in flight, and the exclusive holder's release when there is one. */
    private class Gate {
        var writers = 0
        var exclusiveDone: CompletableDeferred<Unit>? = null
        var drained: CompletableDeferred<Unit>? = null
    }

    /** What this coroutine already holds, so a nested call does not wait for itself. */
    private class Held(val user: String, val exclusive: Boolean) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private suspend fun held(user: String): Held? = coroutineContext[Held]?.takeIf { it.user == user }

    /**
     * Never waits on [exclusive]: before a migration flips the user their records are still in
     * `pubky.app`, so a read routed there is right, and the flip itself is [markV1].
     */
    suspend fun of(user: String): SocialEpoch = of(user, fresh = false)

    private suspend fun of(user: String, fresh: Boolean): SocialEpoch {
        lock.withLock { known[user] }?.let { (epoch, at) ->
            if (epoch == SocialEpoch.V1 || (!fresh && now() - at < V0_TTL_MILLIS)) return epoch
        }
        val epoch = pubky.get(SocialLayout.SocialV1.profile(user)).fold(
            onSuccess = { SocialEpoch.V1 },
            onFailure = { if (it.isNotFound()) SocialEpoch.V0 else throw it },
        )
        // A flip that landed during the read is not undone: V1 is kept for good.
        return lock.withLock {
            val kept = known[user]?.first.takeIf { it == SocialEpoch.V1 } ?: epoch
            known[user] = kept to now()
            kept
        }
    }

    /** For a read. A write goes through [writing], which keeps the answer true until it is done. */
    suspend fun layoutOf(user: String): SocialLayout = SocialLayout.of(of(user))

    /**
     * Runs [block] with [user]'s layout, which no migration of this process changes until [block]
     * returns. A `v0` answer is asked again rather than read from the cache: another device may
     * have flipped the user since, and a write routed to `pubky.app` after that is lost.
     */
    suspend fun <T> writing(user: String, block: suspend (SocialLayout) -> T): T {
        if (held(user) != null) return block(SocialLayout.of(of(user, fresh = true)))
        while (true) {
            val wait = lock.withLock {
                val gate = gates.getOrPut(user) { Gate() }
                gate.exclusiveDone.also { if (it == null) gate.writers++ }
            } ?: break
            wait.await()
        }
        try {
            return withContext(Held(user, exclusive = false)) { block(SocialLayout.of(of(user, fresh = true))) }
        } finally {
            // NonCancellable: a release skipped by a cancelled lock() would hold the user for good.
            withContext(NonCancellable) {
                lock.withLock {
                    val gate = gates.getValue(user)
                    gate.writers--
                    if (gate.writers == 0) gate.drained?.complete(Unit)
                }
            }
        }
    }

    /**
     * Marks [user]'s account as being deleted, until [reinstate] at the next sign-in. A write that
     * was waiting on a hold checks it inside the hold: the session stays live until sign-out, so
     * that alone does not stop a migration or a self-tag from putting the account back.
     */
    suspend fun retire(user: String) {
        lock.withLock { retired += user }
    }

    /** Also asks the homeserver again: the erase may have deleted the v1 profile a cached V1 stood for. */
    suspend fun reinstate(user: String) {
        lock.withLock {
            retired -= user
            known -= user
        }
    }

    suspend fun isRetired(user: String): Boolean = lock.withLock { user in retired }

    /** For Loopky's own write of [user]'s `social/v1/profile.json`, which flips them. */
    suspend fun markV1(user: String) {
        lock.withLock { known[user] = SocialEpoch.V1 to now() }
    }

    /**
     * Runs [block] once every [writing] for [user] has finished, while new ones wait: for
     * the migration, and for anything that must not run beside it, like erasing the account.
     */
    suspend fun <T> exclusive(user: String, block: suspend () -> T): T {
        when (held(user)?.exclusive) {
            true -> return block()
            false -> error("exclusive() inside writing() for the same user would wait for itself")
            null -> Unit
        }
        val mine = CompletableDeferred<Unit>()
        var drained: CompletableDeferred<Unit>? = null
        while (true) {
            val wait = lock.withLock {
                val gate = gates.getOrPut(user) { Gate() }
                gate.exclusiveDone.also { other ->
                    if (other == null) {
                        gate.exclusiveDone = mine
                        drained = if (gate.writers > 0) CompletableDeferred<Unit>().also { gate.drained = it } else null
                    }
                }
            } ?: break
            wait.await()
        }
        try {
            drained?.await()
            return withContext(Held(user, exclusive = true)) { block() }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    val gate = gates.getValue(user)
                    gate.exclusiveDone = null
                    gate.drained = null
                }
            }
            mine.complete(Unit)
        }
    }

    private companion object {
        const val V0_TTL_MILLIS = 5 * 60 * 1000L
    }
}
