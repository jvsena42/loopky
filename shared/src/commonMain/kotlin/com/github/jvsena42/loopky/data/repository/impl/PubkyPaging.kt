package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.util.runSuspendCatching

/**
 * Entries per `list()` page.
 *
 * The homeserver defaults to 100 when the caller sends no `limit`, and refuses more than 1000
 * (`pubky-homeserver/src/constants.rs`: `DEFAULT_LIST_LIMIT`, `DEFAULT_MAX_LIST_LIMIT`). Asking
 * for a fixed page is what makes the cursor loop below terminate on a short page.
 */
internal const val LIST_PAGE_SIZE: UShort = 200u

/**
 * Ceiling on the paging loop. A homeserver that keeps handing back fresh entries forever is a bug
 * on its side, and these listings sit on screen-load paths — spinning is worse than truncating.
 */
private const val MAX_LIST_PAGES = 64

/**
 * Every entry under [prefix], following the homeserver's cursor until it stops returning new ones.
 *
 * **Fails if any page fails.** A partial listing is indistinguishable from a short one, and
 * rendering one is exactly how published decks silently vanished from a library: `list()` has
 * always accepted `cursor`/`limit`, no call site used them, and everything past the server's
 * 100-entry default page was simply invisible.
 *
 * [shallow] asks for one entry per first-level child instead of every record beneath it — a deck
 * directory rather than its manifest, its ~38 chunk records and its media. It is deliberately not
 * *depended* on: if a homeserver ignores the flag the reply is an ordinary deep listing and the
 * same loop still collects all of it, one page at a time. So the fallback needs no detection.
 */
internal suspend fun PubkyClient.listAllEntries(
    prefix: String,
    shallow: Boolean = false,
    pageSize: UShort = LIST_PAGE_SIZE,
): Result<List<String>> = runSuspendCatching {
    val listing = pageThrough(prefix, shallow, pageSize)
    listing.failure?.let { throw it }
    listing.entries
}

/**
 * [listAllEntries], best-effort: whatever pages could be read before a failure, rather than an
 * error. For sweeps that hold a second source of paths and must make progress regardless.
 */
internal suspend fun PubkyClient.listAllEntriesOrEmpty(
    prefix: String,
    shallow: Boolean = false,
): List<String> = pageThrough(prefix, shallow, LIST_PAGE_SIZE).entries

/**
 * [listAllEntries], reporting a partial read rather than throwing on it or swallowing it.
 *
 * The third option between the two above, for a caller that must make progress on what it got
 * *and* must not treat it as the whole truth — persisting a degraded listing as authoritative is
 * how a device ends up painting a library that is missing decks, every launch, with nothing to
 * correct it.
 */
internal suspend fun PubkyClient.listAllEntriesPartial(
    prefix: String,
    shallow: Boolean = false,
): PubkyListing = pageThrough(prefix, shallow, LIST_PAGE_SIZE)

/** What one paging run gathered, and what stopped it — so all three entry points share one loop. */
internal class PubkyListing(
    val entries: List<String>,
    val failure: Throwable?,
    /**
     * The loop gave up rather than reaching the end: [MAX_LIST_PAGES] hit with a full page still
     * coming, or a homeserver repeating a page it had already sent.
     *
     * Separate from [failure] because the two want different answers. Every read here *succeeded* —
     * there is nothing to retry and nothing to report as broken — so [listAllEntries] still hands
     * back what it collected rather than throwing, and a homeserver that ignores `cursor` shows the
     * user their first page instead of an error. But it is not the whole listing, so nothing may
     * persist it as one.
     */
    val truncated: Boolean = false,
) {
    /** Everything under the prefix, and known to be. The only state a cache may be written from. */
    val isComplete: Boolean get() = failure == null && !truncated
}

private suspend fun PubkyClient.pageThrough(
    prefix: String,
    shallow: Boolean,
    pageSize: UShort,
): PubkyListing {
    val seen = linkedSetOf<String>()
    var cursor: String? = null
    var pages = 0
    while (true) {
        // The ceiling is reached with a full page still to come, so what we have is a prefix of the
        // listing, not the listing. Checked here rather than in the `while` condition, which
        // could not tell that from a run that happened to end on its last allowed page.
        if (pages == MAX_LIST_PAGES) return PubkyListing(seen.toList(), null, truncated = true)
        pages++
        val payload = list(prefix, cursor = cursor, limit = pageSize, shallow = shallow.takeIf { it })
            .getOrElse { return PubkyListing(seen.toList(), it) }
        val page = parsePubkyUrlsFromList(payload)
        // A short or empty page is the end of the listing — the ordinary exit.
        if (page.size < pageSize.toInt()) {
            seen.addAll(page)
            break
        }
        // A full page that adds nothing new is a homeserver repeating itself, i.e. ignoring the
        // cursor. Stop rather than loop forever — but say so, because the rest is unread.
        if (!seen.addAll(page)) return PubkyListing(seen.toList(), null, truncated = true)
        cursor = page.lastOrNull()
    }
    return PubkyListing(seen.toList(), null)
}

/** The FFI `list` payload is a JSON array of `pubky://…` URL strings, deep or shallow alike. */
internal fun parsePubkyUrlsFromList(payload: String): List<String> =
    runCatching { loopkyJson.decodeFromString<List<String>>(payload) }
        .getOrDefault(emptyList())
        .filter { it.startsWith("pubky://") }
