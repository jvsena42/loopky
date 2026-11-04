package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.AccountStamp
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The signed-in account's blocks — pubky.app's mute records — for the session, shared by
 * [DiscoveryRepositoryImpl], which writes them, and [DeckRepositoryImpl], which hides a blocked
 * author's decks from every followed-deck list (#577). One instance per graph, or a block made in
 * one repository is invisible to the other until the next launch.
 */
class BlockedAccounts(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
) {
    private var cache: MutableSet<String>? = null
    private val lock = Mutex()
    private val account = AccountStamp(session)

    /** Throws when the homeserver cannot be read; empty for a guest. */
    suspend fun load(): Set<String> {
        lock.withLock {
            if (account.changed()) cache = null
            cache?.toSet()
        }?.let { return it }
        val owner = session.current()?.identity?.pubky ?: return emptySet()
        // Paged: the directory is pubky.app's, and an account muting there can pass one page.
        val entries = pubky.listAllEntries(PubkyPaths.mutesRoot(owner))
            .getOrElse { if (it.isNotFound()) null else throw it }
        val muted = entries.orEmpty().mapNotNull { url ->
            url.substringAfter(MUTES_MARKER, missingDelimiterValue = "")
                .substringBefore('/')
                .takeIf { it.isNotEmpty() }
        }.toSet()
        lock.withLock {
            cache = muted.toMutableSet()
            account.mark()
        }
        return muted
    }

    /**
     * [load] for a read that filters: an unreadable mute list hides nobody rather than failing
     * the read it sits under.
     */
    suspend fun loadOrEmpty(): Set<String> =
        runSuspendCatching { load() }
            .onFailure { Log.w(TAG, "load: FAILED, hiding nobody — ${it.message}") }
            .getOrElse { emptySet() }

    /** What is already known this session, without a request — for reads that must not wait. */
    suspend fun known(): Set<String> = lock.withLock {
        if (account.changed()) cache = null
        cache?.toSet().orEmpty()
    }

    /**
     * Loaded first when cold, so [pubky] joins the real list rather than standing in for it — the
     * trap a one-element follow set once fell into.
     */
    suspend fun add(pubky: String) {
        runSuspendCatching { load() }
        lock.withLock { cache?.add(pubky) }
    }

    suspend fun remove(pubky: String) {
        lock.withLock { cache?.remove(pubky) }
    }

    private companion object {
        const val TAG = "Loopky/BlockedAccounts"
        const val MUTES_MARKER = "/pub/pubky.app/mutes/"
    }
}
