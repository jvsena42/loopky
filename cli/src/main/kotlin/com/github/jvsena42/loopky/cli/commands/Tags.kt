package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliEnvironment
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.data.repository.TagRepository
import kotlinx.serialization.Serializable

@Serializable
data class TagTrendingResult(
    val tags: List<TrendingTagView>,
    /**
     * The indexer these came from, repeated inside the payload as well as in the envelope.
     *
     * This is the one command whose *entire* output comes from Nexus, so it is the only one where
     * a mismatched network produces a plausible-looking wrong answer rather than an obviously
     * empty one. Worth being able to see without unwrapping the envelope.
     */
    val indexer: String,
)

@Serializable
data class TrendingTagView(val label: String)

/**
 * Trending deck tags, read from the Nexus indexer.
 *
 * No session and no capability involved — this is a plain HTTP read against a public index, not a
 * homeserver record. It is here because a deck author choosing labels wants to know what already
 * exists; it is worth asking separately whether a loopky-namespace-only client should carry it at
 * all, since Nexus surfaces pubky.app-graph tags that resource tags never join
 * (Architecture.md §7.7).
 */
suspend fun tagTrending(
    args: Args,
    tags: TagRepository,
    environment: CliEnvironment,
): CommandResult {
    // Validated rather than defaulted: `--limit twenty` silently becoming 20, or `--limit 0`
    // passing through, is a silently different N for the one command whose entire output is a
    // ranked list — the same class of quiet wrongness the envelope's `indexer` field exists to
    // prevent elsewhere.
    val limit = args.positiveInt("limit", DEFAULT_TRENDING_LIMIT)
    // `trendingDeckTags` answers with labels in order, not with counts: Nexus ranks them and the
    // ranking is the information. A fabricated count beside each one would read as data.
    val trending = tags.trendingDeckTags(limit = limit)
        .getOrElse { throw asCliError(it) }
        .map { TrendingTagView(it.value) }
    return result(
        TagTrendingResult(trending, environment.indexer),
        if (trending.isEmpty()) {
            "No trending tags on ${environment.name} (${environment.indexer})."
        } else {
            trending.joinToString("\n") { it.label }
        },
    )
}

private const val DEFAULT_TRENDING_LIMIT = 20
