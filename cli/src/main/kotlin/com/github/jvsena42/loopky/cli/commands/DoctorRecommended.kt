package com.github.jvsena42.loopky.cli.commands

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Hosts no command needs but an agent building a deck will: where card pictures are found and
 * served, in the skill's fallback order (`plugins/loopky/skills/loopky/SKILL.md`). The same on
 * every environment, and never able to fail `doctor`.
 */
internal val RECOMMENDED_HOSTS = listOf(
    "upload.wikimedia.org" to "card pictures (Wikimedia images; --check-images)",
    "commons.wikimedia.org" to "finding card pictures and their URLs",
    "api.openverse.org" to "finding openly licensed card pictures",
    "images-api.nasa.gov" to "finding space pictures",
    "api.artic.edu" to "finding public-domain art",
    "collectionapi.metmuseum.org" to "finding public-domain art",
)

internal suspend fun probeRecommended(probe: suspend (String) -> ProbeOutcome): List<HostCheck> = coroutineScope {
    RECOMMENDED_HOSTS.map { (host, neededFor) ->
        async { probe("https://$host/").let { HostCheck(host, neededFor, it.status, it.detail, it.millis) } }
    }.awaitAll()
}

/** A blocked picture host is still worth asking the human for, in the same breath as the rest. */
internal fun withRecommended(step: String?, missing: List<String>): String? {
    if (missing.isEmpty()) return step
    val ask = "Optionally, also ask for ${missing.joinToString(" ")}: card pictures on Wikimedia, and " +
        "--check-images against them, need these."
    return step?.let { "$it $ask" } ?: ask
}
