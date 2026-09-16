package com.github.jvsena42.loopky.data.nexus

import com.github.jvsena42.loopky.data.repository.impl.loopkyJson
import com.github.jvsena42.loopky.util.encodeUriComponent
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Read-only client for the Pubky Nexus indexer (`/v0` REST API), which aggregates the whole network
 * — the only way to answer global questions a single homeserver cannot. All calls are
 * unauthenticated public reads.
 *
 * Loopky writes tag records to the user's homeserver in the pubky-app-specs format and Nexus indexes
 * them from there, so reads and writes meet without Loopky running a backend.
 */
class NexusClient(
    private val http: HttpFetcher,
    /**
     * Which indexer this build talks to. Deliberately has no default: staging and production index
     * different networks, so a missing wire-up must fail to compile rather than quietly ship a
     * release pointed at staging (#42). Platform modules supply it from the injected
     * `PubkyEnvironment.nexusBaseUrl`, so it cannot end up on a different network from the
     * homeserver the app publishes to (#205).
     *
     * Public because the indexer also serves profile pictures, which callers build URLs for.
     */
    val baseUrl: String,
) {

    /** Tag labels starting with [prefix] — powers tag-input autocomplete. */
    suspend fun searchTagsByPrefix(
        prefix: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val encoded = encodeUriComponent(prefix)
        withIndexerRetry("searchTagsByPrefix") {
            val body = http.get("$baseUrl/v0/search/tags/by_prefix/$encoded?limit=$limit").getOrThrow()
            loopkyJson.decodeFromString(ListSerializer(String.serializer()), body)
        }
    }

    /**
     * Pubkys whose profile name starts with [prefix] — the people half of Loopky's search box.
     * Indexes every pubky.app profile, not only accounts that have opened Loopky, so callers decide
     * which matches are worth showing. Lexicographic on the indexed name: a prefix, not a substring,
     * so "ada" finds "Ada Lovelace" and never "Grace Ada".
     */
    suspend fun searchUsersByName(
        prefix: String,
        limit: Int = DEFAULT_USER_SEARCH_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val url = "$baseUrl/v0/search/users/by_name/${encodeUriComponent(prefix)}" +
            "?limit=${limit.coerceIn(1, MAX_USER_SEARCH_LIMIT)}"
        withIndexerRetry("searchUsersByName") {
            val body = http.get(url).getOrThrow()
            loopkyJson.decodeFromString(ListSerializer(String.serializer()), body)
        }
    }

    /**
     * The same search over pubkys rather than names. Nexus rejects a prefix shorter than
     * [MIN_USER_ID_PREFIX] outright, so callers must not ask below it; a full pubky needs no search
     * and should be opened directly.
     */
    suspend fun searchUsersById(
        prefix: String,
        limit: Int = DEFAULT_USER_SEARCH_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val url = "$baseUrl/v0/search/users/by_id/${encodeUriComponent(prefix)}" +
            "?limit=${limit.coerceIn(1, MAX_USER_SEARCH_LIMIT)}"
        withIndexerRetry("searchUsersById") {
            val body = http.get(url).getOrThrow()
            loopkyJson.decodeFromString(ListSerializer(String.serializer()), body)
        }
    }

    /**
     * Loopky resources carrying [label] — the label → URIs read behind global browse. Only tag
     * records written outside the pubky.app namespace reach this index, which is why deck tags live
     * under `/pub/loopky/tags/`. `app` is that namespace, so this cannot return another app's
     * resources.
     *
     * [skip] indexes the indexer's *raw* sorted set, not the entries that come back: Nexus drops a
     * resource whose details no longer resolve, so a page is routinely shorter than [limit] with
     * more behind it. A short page is therefore never evidence of the end — only an empty one is.
     * Measured on staging: `limit=100&skip=0` returned 69 of 71, and `skip=40` surfaced two the
     * first page had never shown.
     *
     * See [NexusResourceSorting] for why paging and [NexusResourceSorting.TaggersCount] do not mix.
     */
    suspend fun resourcesByTag(
        label: String,
        limit: Int = DEFAULT_RESOURCE_LIMIT,
        skip: Int = 0,
        sorting: NexusResourceSorting = NexusResourceSorting.TaggersCount,
    ): Result<List<NexusResourceDto>> = runSuspendCatching {
        val url = buildString {
            append("$baseUrl/v0/stream/resources")
            append("?app=$LOOPKY_APP")
            append("&tags=${encodeUriComponent(label)}")
            append("&sorting=${sorting.wire}")
            append("&limit=${limit.coerceIn(1, MAX_RESOURCE_LIMIT)}")
            append("&skip=$skip")
        }
        withIndexerRetry("resourcesByTag") {
            val body = http.get(url).getOrThrow()
            loopkyJson.decodeFromString(ListSerializer(NexusResourceDto.serializer()), body)
        }
    }

    /**
     * Every label on one resource with its distinct-tagger count — the read behind "N people
     * follow this deck". Fails with a 404 [HttpError] when nothing has ever tagged [uri].
     */
    suspend fun resourceByUri(uri: String): Result<NexusResourceTagsDto> = runSuspendCatching {
        val url = "$baseUrl/v0/resource/by-uri" +
            "?uri=${encodeUriComponent(uri)}" +
            "&limit_tags=$MAX_TAGS_PER_RESOURCE" +
            "&limit_taggers=1"
        withIndexerRetry("resourceByUri") {
            val body = http.get(url).getOrThrow()
            loopkyJson.decodeFromString(NexusResourceTagsDto.serializer(), body)
        }
    }

    /**
     * Pubkys whose **profile** carries [label] — the read the `loopky-user` directory is built on.
     *
     * Replaces `/v0/tags/taggers/{label}`, which looks like this query and is not one: without a
     * `user_id` that endpoint answers out of the hot-tags Redis cache, so a label that never reached
     * the top-N network-wide comes back `[]`, indistinguishable from nobody having used it. Prod's
     * `User` hot list bottomed out at 66 tagged users while `loopky-user` had 2 (#134). Its
     * reach-scoped branch is hardcoded to `Post` subjects (pubky/pubky-nexus#1036).
     *
     * **Fails with a 404 [HttpError] on an indexer that predates the endpoint** (pubky-nexus#1030,
     * live on staging but not prod), so callers must fall back rather than treat it as "no Loopky
     * users".
     *
     * Untrusted like every other tag read: `score` counts taggers and says nothing about *who*
     * tagged. Verify with [userTaggers].
     */
    suspend fun usersByProfileTag(
        label: String,
        limit: Int = DEFAULT_PROFILE_TAG_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        // The endpoint takes a comma-separated `tags` list and ORs it; Loopky asks one label at a
        // time, so the multi-label scoring is deliberately not relied on here.
        val url = "$baseUrl/v0/search/users/by_tags" +
            "?tags=${encodeUriComponent(label)}" +
            "&limit=${limit.coerceIn(1, MAX_PROFILE_TAG_LIMIT)}"
        withIndexerRetry("usersByProfileTag") {
            val body = http.get(url).getOrThrow()
            loopkyJson.decodeFromString(ListSerializer(NexusScoredUserDto.serializer()), body)
                .map { it.user_id }
        }
    }

    /**
     * Authors of the posts carrying [label], most recent first, each pubky once. Post tags are the
     * one Loopky label that reaches the global tag index (§7.7 point 5), and a `post_key` is
     * `{author}:{post_id}` — so every deck announcement's author falls out without fetching a post.
     */
    suspend fun postAuthorsByTag(
        label: String,
        limit: Int = DEFAULT_POST_SEARCH_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val url = "$baseUrl/v0/search/posts/by_tag/${encodeUriComponent(label)}" +
            "?limit=${limit.coerceIn(1, MAX_POST_SEARCH_LIMIT)}"
        withIndexerRetry("postAuthorsByTag") {
            val body = http.get(url).getOrThrow()
            loopkyJson.decodeFromString(ListSerializer(NexusPostKeyDto.serializer()), body)
                .mapNotNull { it.post_key.substringBefore(':').takeIf { author -> author.isNotEmpty() } }
                .distinct()
        }
    }

    /**
     * Who tagged user [userId] with [label]. Used to tell a self-tag from someone labelling
     * a stranger: only the former has [userId] among the taggers.
     */
    suspend fun userTaggers(
        userId: String,
        label: String,
        limit: Int = DEFAULT_USER_TAGGERS_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val url = "$baseUrl/v0/user/${encodeUriComponent(userId)}/taggers/${encodeUriComponent(label)}" +
            "?limit=${limit.coerceIn(1, MAX_USER_TAGGERS_LIMIT)}"
        withIndexerRetry("userTaggers") {
            val body = http.get(url).getOrThrow()
            loopkyJson.decodeFromString(NexusTaggersDto.serializer(), body).users
        }
    }

    /**
     * Pubkys that follow [userId], most recent first. The one social read a homeserver cannot
     * answer: a follow record lives on the *follower's* homeserver, so this is a network-wide
     * reverse lookup. The forward direction needs nothing from here — list
     * `/pub/pubky.app/follows/` on the user's own homeserver, which is cheaper and first-hand.
     */
    suspend fun followers(
        userId: String,
        limit: Int = DEFAULT_FOLLOWS_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val url = "$baseUrl/v0/user/${encodeUriComponent(userId)}/followers" +
            "?limit=${limit.coerceIn(1, MAX_FOLLOWS_LIMIT)}"
        withIndexerRetry("followers") {
            val body = http.get(url).getOrThrow()
            loopkyJson.decodeFromString(ListSerializer(String.serializer()), body)
        }
    }

    companion object {
        /** The `/pub/{app}/tags/` segment Loopky writes deck tag records under. */
        const val LOOPKY_APP = "loopky"

        /** Nexus rejects a shorter pubky prefix than this on `/search/users/by_id`. */
        const val MIN_USER_ID_PREFIX = 3

        private const val DEFAULT_SEARCH_LIMIT = 10
        private const val DEFAULT_USER_SEARCH_LIMIT = 20
        private const val MAX_USER_SEARCH_LIMIT = 100
        private const val DEFAULT_RESOURCE_LIMIT = 30
        private const val MAX_RESOURCE_LIMIT = 100
        private const val MAX_TAGS_PER_RESOURCE = 100
        private const val DEFAULT_PROFILE_TAG_LIMIT = 50
        private const val MAX_PROFILE_TAG_LIMIT = 200
        private const val DEFAULT_POST_SEARCH_LIMIT = 100
        private const val MAX_POST_SEARCH_LIMIT = 200
        private const val DEFAULT_USER_TAGGERS_LIMIT = 40
        private const val MAX_USER_TAGGERS_LIMIT = 100
        private const val DEFAULT_FOLLOWS_LIMIT = 60
        private const val MAX_FOLLOWS_LIMIT = 200
    }
}

/**
 * How `/v0/stream/resources` orders a page. Nexus accepts these two and rejects anything else.
 *
 * **Only [Timeline] can be paged.** [TaggersCount] re-ranks whenever anyone tags anything, so a
 * `skip`-based page 2 taken a moment after page 1 can repeat entries and skip others — there is no
 * stable position to resume from. [Timeline] orders by `indexed_at` descending, which only ever
 * grows at the head, so a cursor stays meaningful.
 *
 * [TaggersCount] is also a poor popularity signal for a deck: the resource-level count sums
 * *distinct taggers across every label*, so a deck whose author typed five topics outranks one four
 * people actually follow. Measured on staging, that put 7 of the top 12 decks under a single author
 * while 11 decks sat at the bottom permanently unreachable (#321).
 */
enum class NexusResourceSorting(val wire: String) {
    /** `indexed_at` descending — newest first, and the only order with a stable cursor. */
    Timeline("timeline"),

    /** Distinct taggers summed over every label on the resource. Unstable under paging. */
    TaggersCount("taggers_count"),
}

/** Identity of an indexed resource (Nexus `ResourceDetails`). */
@Serializable
data class NexusResourceDetailsDto(
    val id: String = "",
    val uri: String,
    val scheme: String = "",
    val indexed_at: Long = 0,
)

/**
 * One label on a resource or user (Nexus `TagDetails`). [taggers] is capped by the request's
 * `limit_taggers`, so count distinct taggers with [taggers_count], never `taggers.size`.
 */
@Serializable
data class NexusTagDetailsDto(
    val label: String,
    val taggers: List<String> = emptyList(),
    val taggers_count: Int = 0,
)

/** One entry of `GET /v0/stream/resources` (Nexus `ResourceView`). */
@Serializable
data class NexusResourceDto(
    val details: NexusResourceDetailsDto,
    val tags: List<NexusTagDetailsDto> = emptyList(),
    val taggers_count: Int = 0,
)

/** `GET /v0/resource/by-uri` (Nexus `ResourceTagsResponse`). */
@Serializable
data class NexusResourceTagsDto(
    val resource: NexusResourceDetailsDto,
    val tags: List<NexusTagDetailsDto> = emptyList(),
)

/**
 * One hit of `GET /v0/search/users/by_tags` (Nexus scored user). [score] is a tagger count, not
 * evidence of a self-tag — see [NexusClient.usersByProfileTag].
 */
@Serializable
data class NexusScoredUserDto(
    val user_id: String,
    val score: Double = 0.0,
)

/** One hit of `GET /v0/search/posts/by_tag/{label}`. [post_key] is `{author}:{post_id}`. */
@Serializable
data class NexusPostKeyDto(
    val post_key: String,
)

/** `GET /v0/user/{id}/taggers/{label}` (Nexus `TaggersInfoResponse`). */
@Serializable
data class NexusTaggersDto(
    val users: List<String> = emptyList(),
    val relationship: Boolean = false,
)
