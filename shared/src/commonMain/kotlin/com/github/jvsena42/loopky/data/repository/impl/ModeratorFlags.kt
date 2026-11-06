package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Accounts pubky.app's moderation bot has tagged with a violation label, which Loopky hides like a
 * block for every reader, guests included (#577). The bot (pubky-moderation) tags the profile it
 * judged under its own `/pub/pubky.app/tags/`, which Nexus indexes. A label only counts when
 * [botPubky] is among its **taggers**: anyone can write `violence` on anyone.
 *
 * Every failure hides nobody: this filters reads, and an unreachable indexer must not fail them.
 */
class ModeratorFlags(
    private val nexus: NexusClient,
    private val botPubky: String,
) {
    private var cache: Set<String>? = null
    private val lock = Mutex()

    /** Every flagged account the indexer lists, once per process. */
    suspend fun flagged(): Set<String> {
        lock.withLock { cache }?.let { return it }
        val candidates = LABELS.toList()
            .mapConcurrently(limit = LABELS.size) { label ->
                nexus.usersByProfileTag(label, CANDIDATE_LIMIT)
                    .onFailure { Log.w(TAG, "flagged('$label'): FAILED, hiding nobody — ${it.message}") }
                    .getOrElse { emptyList() }
                    .map { it to label }
            }
            .flatten()
            .distinct()
        val flagged = candidates
            .mapConcurrently(limit = IN_FLIGHT) { (pubky, label) -> pubky.takeIf { taggedByBot(it, label) } }
            .filterNotNull()
            .toSet()
        lock.withLock { cache = flagged }
        return flagged
    }

    /** One account, asked directly — for a profile or deck reached by a link. */
    suspend fun isFlagged(pubky: String): Boolean =
        lock.withLock { cache }?.contains(pubky) == true ||
            LABELS.toList().mapConcurrently(limit = LABELS.size) { taggedByBot(pubky, it) }.any { it }

    private suspend fun taggedByBot(pubky: String, label: String): Boolean =
        runSuspendCatching {
            nexus.userTaggers(pubky, label, TAGGERS_LIMIT).getOrThrow().contains(botPubky)
        }.getOrElse { false }

    companion object {
        private const val TAG = "Loopky/ModeratorFlags"
        private const val CANDIDATE_LIMIT = 100
        private const val IN_FLIGHT = 8
        private const val TAGGERS_LIMIT = 100

        /**
         * The labels pubky-moderation's `ViolationType::to_tag` writes, spelling included
         * (`harassement` is theirs). A label added there is not hidden here until added here.
         */
        val LABELS: Set<String> = setOf(
            "hatespeech",
            "harassement",
            "terrorism",
            "violence",
            "illegal_activities",
            "il_adult_nu_sex_act",
            "nudity",
        )
    }
}
