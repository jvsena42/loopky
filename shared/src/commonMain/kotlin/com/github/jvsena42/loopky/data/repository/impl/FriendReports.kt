package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.AccountStamp
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Accounts someone the reader follows has reported, which discovery hides like a block (#577).
 * A report writes [ReservedTags.REPORTED] on the reported profile; anyone can write that label on
 * anyone, so a tag only counts when one of its **taggers** is a followee — the tag's score says
 * nothing about who wrote it.
 *
 * Every failure hides nobody: this filters reads, and an unreachable indexer must not fail them.
 */
class FriendReports(
    private val nexus: NexusClient,
    session: SessionProvider,
) {
    private var cache: Pair<Set<String>, Set<String>>? = null
    private val lock = Mutex()
    private val account = AccountStamp(session)

    /** The reported accounts among everyone tagged, for this follow set. Cached per follow set. */
    suspend fun hiddenFor(following: Set<String>): Set<String> {
        if (following.isEmpty()) return emptySet()
        lock.withLock {
            if (account.changed()) cache = null
            cache?.takeIf { it.first == following }?.second
        }?.let { return it }
        val label = ReservedTags.REPORTED.value
        val candidates = nexus.usersByProfileTag(label, CANDIDATE_LIMIT)
            .onFailure { Log.w(TAG, "hiddenFor: candidates FAILED, hiding nobody — ${it.message}") }
            // Not cached: a failed read hides nobody this once, not for the rest of the session.
            .getOrElse { return emptySet() }
        val hidden = candidates.distinct()
            .filterNot { it in following }
            .mapConcurrently(limit = IN_FLIGHT) { candidate -> candidate.takeIf { reportedBy(it, following) } }
            .filterNotNull()
            .toSet()
        lock.withLock {
            cache = following to hidden
            account.mark()
        }
        return hidden
    }

    /** One account, asked directly — for a profile or deck reached by a link. */
    suspend fun isReported(pubky: String, following: Set<String>): Boolean =
        following.isNotEmpty() && pubky !in following && reportedBy(pubky, following)

    private suspend fun reportedBy(pubky: String, following: Set<String>): Boolean =
        runSuspendCatching {
            nexus.userTaggers(pubky, ReservedTags.REPORTED.value, TAGGERS_LIMIT).getOrThrow()
                .any { it in following }
        }.getOrElse { false }

    private companion object {
        const val TAG = "Loopky/FriendReports"
        const val CANDIDATE_LIMIT = 100
        const val IN_FLIGHT = 8
        const val TAGGERS_LIMIT = 100
    }
}
