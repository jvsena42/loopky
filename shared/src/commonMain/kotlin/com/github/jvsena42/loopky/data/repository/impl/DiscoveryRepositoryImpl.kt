package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.nexus.NexusResourceSorting
import com.github.jvsena42.loopky.data.pubky.AccountStamp
import com.github.jvsena42.loopky.data.pubky.FollowDto
import com.github.jvsena42.loopky.data.pubky.PostDto
import com.github.jvsena42.loopky.data.pubky.PostEmbedDto
import com.github.jvsena42.loopky.data.pubky.PostIds
import com.github.jvsena42.loopky.data.pubky.PostKinds
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyLinks
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.PubkyUris
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.repository.DeckPage
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.PeoplePage
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * [DiscoveryRepository] backed by [PubkyClient]. Follows are pubky.app-native records
 * (`/pub/pubky.app/follows/{followee}`) on the follower's homeserver, with a [Mutex]-guarded
 * in-memory follow-set cache for the session.
 *
 * [decksByTag] is a local filter over decks the user can already reach. [decksByTagGlobal] and
 * [loopkyUsers] go wider through the Nexus indexer, and everything they read is untrusted, so each
 * entry is verified before it is returned (see [verifiedDeck]).
 *
 * The discovery reads leave out the signed-in account's own decks — Library is where those live,
 * and on a young network they are otherwise most of what Discover has to show.
 */
// Nine collaborators is a lot, but every one is a distinct source this repo has to join —
// homeserver, session, deck/tag/identity repos, indexer, and the share preference that gates
// announcing — and folding any pair into a wrapper would exist only to shorten this list.
@Suppress("LongParameterList")
class DiscoveryRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val deckRepository: DeckRepository,
    private val tagRepository: TagRepository,
    private val identityRepository: IdentityRepository,
    private val nexus: NexusClient,
    private val preferences: AppPreferences,
) : DiscoveryRepository {

    /** Followee pubkys for the session, or null until first loaded from the homeserver. */
    private var cache: MutableSet<String>? = null
    private val cacheLock = Mutex()

    /** Guarded by [cacheLock]. The follow set is per-account and must not outlive it. */
    private val cacheAccount = AccountStamp(session)

    override suspend fun following(): List<String> {
        // Checked before the cache is served — see the same guard in `loadSubscriptions`. Served
        // first, a new account inherits whoever the last one followed.
        cacheLock.withLock {
            if (cacheAccount.changed()) cache = null
            cache
        }?.let { return it.toList() }
        val owner = session.current()?.identity?.pubky ?: return emptyList()
        // Propagate transport failures like DeckRepositoryImpl.listByAuthor: "couldn't reach the
        // homeserver" must not render as "you follow nobody". Paged, because `follows/` is shared
        // with pubky.app proper — an account active there passes the 100-record default page
        // easily, and a truncated set makes `isFollowing` deny someone you do follow.
        val entries = pubky.listAllEntries(PubkyPaths.followsRoot(owner))
            .getOrElse { if (it.isNotFound()) null else throw it }
        val followees = entries?.let(::parseFollowees) ?: emptyList()
        cacheLock.withLock {
            cache = followees.toMutableSet()
            cacheAccount.mark()
        }
        return followees
    }

    override suspend fun isFollowing(pubky: String): Boolean = following().contains(pubky)

    override suspend fun followUser(pubky: String): Result<Unit> = runSuspendCatching {
        val owner = session.requireSession().identity.pubky
        val body = loopkyJson.encodeToString(FollowDto(created_at = epochMillis()))
        this.pubky.putWithSessionRetry(PubkyPaths.follow(owner, pubky), body, session, revalidator)
            .getOrThrow()
        // Updates the cache only if it has already been loaded, matching `followDeck`'s handling
        // of the subscription map. Seeding it from a single follow — which is what this did —
        // published a one-element set as the complete follow list to the next `following()`, and
        // left it stamped to nobody, so it also survived a change of account.
        cacheLock.withLock { cache?.add(pubky) }
        Unit
    }

    override suspend fun unfollowUser(pubky: String): Result<Unit> = runSuspendCatching {
        val owner = session.requireSession().identity.pubky
        this.pubky.deleteWithSessionRetry(PubkyPaths.follow(owner, pubky), session, revalidator)
            .getOrThrow()
        cacheLock.withLock { cache?.remove(pubky) }
        Unit
    }

    override suspend fun announceDeck(announcement: DeckAnnouncement): Result<PubkyUri> =
        runSuspendCatching {
            // The gate lives next to the write, not only in the callers. "Off" in #39 means
            // nothing reaches the homeserver, and three separate ViewModels each remembering to
            // check is three chances to post behind the user's back.
            check(preferences.shareOnPubky.first()) { "Sharing on Pubky is turned off" }
            val owner = session.requireSession().identity.pubky
            // Microseconds is what pubky-app-specs encodes, and epochMillis() is the only clock
            // commonMain has. Two announcements inside one millisecond would land on one id, which
            // takes two taps a millisecond apart — the id is a timestamp, not a uniqueness claim.
            val postId = PostIds.create(epochMillis() * MICROS_PER_MILLI)
            val body = loopkyJson.encodeToString(
                PostDto(
                    content = announcement.content,
                    // A link post either way: the body always carries the deck's URI, and the cover
                    // travels as a link in the body too. `attachments` is left empty on purpose —
                    // pubky.app resolves it strictly as pubky.app file records, so a URL there was
                    // invisible. See DeckAnnouncement.content.
                    kind = PostKinds.LINK,
                    // A `short` embed is how Nexus spells "repost", and it then demands the
                    // embedded URI already be an indexed post — see PostKinds.
                    embed = PostEmbedDto(kind = PostKinds.LINK, uri = announcement.deckUri.value),
                ),
            )
            val path = PubkyPaths.post(owner, postId)
            this.pubky.putWithSessionRetry(path, body, session, revalidator).getOrThrow()
            Log.d(TAG, "announceDeck: ${announcement.kind} -> $path")
            val uri = PubkyUri(path)
            tagAnnouncement(uri, announcement.tags)
            uri
        }.onFailure { Log.w(TAG, "announceDeck: FAILED — ${it.message}", it) }

    /**
     * Label the announcement post with the deck's topics and [ReservedTags.DECK].
     *
     * **This is the only way a deck's topics can ever trend.** Nexus admits a label into its global
     * tag index only when the subject is a pubky.app post or profile; a deck manifest can only be a
     * generic resource, so the manifest tags Loopky writes are invisible to `/v0/tags/hot` and every
     * other app's feed. The manifest tags stay regardless — they are how Loopky finds its own decks,
     * and they keep working when announcing is off (Architecture.md §7.7).
     *
     * Best-effort, one label at a time. Written after the post so the subject exists.
     */
    private suspend fun tagAnnouncement(postUri: PubkyUri, tags: List<Tag>) {
        for (tag in tags) {
            val result = if (ReservedTags.isReserved(tag)) {
                tagRepository.putReservedTag(postUri, tag)
            } else {
                tagRepository.putTag(postUri, tag)
            }
            result.onFailure { Log.w(TAG, "announceDeck: tag '${tag.value}' FAILED — ${it.message}") }
        }
    }

    override suspend fun followingProfiles(pubky: String): List<PubkyIdentity> {
        val followees = if (pubky == session.current()?.identity?.pubky) {
            // The session cache already holds this, and it includes a follow made a moment ago
            // that no indexer has seen yet.
            following()
        } else {
            val entries = this.pubky.listAllEntries(PubkyPaths.followsRoot(pubky))
                .getOrElse { if (it.isNotFound()) null else throw it }
            entries?.let(::parseFollowees) ?: emptyList()
        }
        return loopkyAccountsAmong(followees, exclude = pubky)
    }

    override suspend fun followerProfiles(pubky: String): List<PubkyIdentity> {
        val followers = nexus.followers(pubky)
            .onFailure { Log.w(TAG, "followerProfiles: FAILED — ${it.message}") }
            .getOrElse { emptyList() }
        return loopkyAccountsAmong(followers, exclude = pubky)
    }

    /**
     * The [candidates] that are Loopky accounts, as resolved profiles, in the order given.
     * [exclude] drops the person whose list this is — seeing yourself in your own follower list is
     * a puzzle rather than information.
     */
    private suspend fun loopkyAccountsAmong(
        candidates: List<String>,
        exclude: String,
    ): List<PubkyIdentity> {
        val considered = candidates
            .filterNot { it == exclude }
            .take(DiscoveryRepository.MAX_FOLLOW_CANDIDATES)
        // keepUnresolved: the self-tag already proved them a Loopky user, so a profile that fails
        // to resolve downgrades the entry to a bare pubky instead of dropping a real account out
        // of the list — the same rule [suggestedPeople] applies to deck authors.
        val kept = considered
            .mapConcurrently { verifiedUser(it, keepUnresolved = true) }
            .filterNotNull()
        Log.d(TAG, "loopkyAccountsAmong: ${kept.size} Loopky of ${candidates.size} follows")
        return kept
    }

    override suspend fun decksFromFollowing(): List<Deck> {
        val me = session.current()?.identity?.pubky
        // Following yourself is reachable, and it would put your own decks on a Discover strip.
        val followees = following().filterNot { it == me }
        val considered = followees.take(DiscoveryRepository.MAX_FOLLOWED_DECK_AUTHORS)
        // Said out loud rather than trimmed quietly: a strip that is short because the tail was
        // cut looks exactly like a strip that is short because nobody published.
        if (considered.size < followees.size) {
            Log.w(
                TAG,
                "decksFromFollowing: querying ${considered.size} of ${followees.size} follows",
            )
        }
        // Concurrent, not a serial loop — measured at ~6.5s per author on a real account, still
        // going minutes after Discover opened.
        //
        // **At the default MAX_IN_FLIGHT, and do not raise it here.** These requests do not spread
        // across servers: Nexus indexes exactly one homeserver and Loopky's follows are the
        // pubky.app graph, so every followee is hosted there — 22 of 22 resolvable authors sampled
        // off a real account had the same target. This is N requests at ONE homeserver, the 429 case
        // MAX_IN_FLIGHT was measured against. The wall-clock buys a pkarr resolution per author, and
        // 9 of 31 sampled followees have no `_pubky` record at all, so they can only time out.
        //
        // The per-author catch has to stay inside the transform: [mapConcurrently] fails fast, so
        // one unresolvable author would otherwise cancel every other request. `runSuspendCatching`
        // still lets cancellation through.
        return considered
            .mapConcurrently { author ->
                runSuspendCatching { deckRepository.listByAuthor(author) }.getOrElse {
                    Log.e(TAG, "decksFromFollowing: listByAuthor failed for $author — ${it.message}", it)
                    emptyList()
                }
            }
            .flatten()
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun decksByTag(tag: Tag): List<Deck> {
        val following = decksFromFollowing()
        val own = runSuspendCatching { deckRepository.listOwned() }.getOrElse { emptyList() }
        return (following + own)
            .distinctBy { it.id }
            .filter { tag in it.tags }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun decksByTagGlobalPage(
        tag: Tag,
        limit: Int,
        cursor: Int,
        alsoTagged: Set<Tag>,
    ): DeckPage {
        val kept = mutableListOf<Deck>()
        // Author-scoped, not by deck id alone: two authors can publish the same deck id, and the
        // `distinctBy { it.id }` this replaced dropped the second one as a duplicate.
        val seen = mutableSetOf<String>()
        var skip = cursor
        var requests = 0
        var exhausted = false

        while (kept.size < limit && requests < MAX_REFILL_REQUESTS) {
            requests++
            val window = limit - kept.size
            val subjects = tagRepository.taggedSubjects(tag, window, skip, NexusResourceSorting.Timeline)
            // Advanced by what was *asked for*, never by what came back: `skip` is an offset into
            // the indexer's raw sorted set and a page is routinely short of entries it dropped
            // itself. Advancing by `subjects.size` would re-read those positions forever.
            skip += window
            if (subjects.isEmpty()) {
                exhausted = true
                break
            }
            subjects
                .mapConcurrently { subject -> verifiedDeck(subject) }
                .filterNotNull()
                // After verification, off the manifest: the indexer's tag list is cut at five.
                .filter { deck -> deck.tags.containsAll(alsoTagged) }
                .forEach { deck -> if (seen.add(deck.authorPubky + "/" + deck.id)) kept += deck }
        }

        Log.d(
            TAG,
            "decksByTagGlobalPage('${tag.value}' + ${alsoTagged.map { it.value }}): " +
                "${kept.size}/$limit in $requests requests, " +
                "cursor $cursor -> $skip, hasMore=${!exhausted}",
        )
        return DeckPage(decks = kept, nextCursor = skip, hasMore = !exhausted)
    }

    /**
     * Turn one indexer entry into a deck, or `null` if it fails any check. Anyone can tag any URI
     * with any label, so a claim is worth only what it resolves to (#40): the URI has to be shaped
     * like a deck manifest, the tagger has to be the deck's own author, and the manifest has to
     * fetch and parse — which is what makes a forged entry useless rather than merely unlikely.
     *
     * Then one rule about relevance rather than trust: a deck of your own is dropped, checked off
     * the URI before the manifest fetch so it costs nothing to skip.
     */
    private suspend fun verifiedDeck(subject: TaggedSubject): Deck? {
        val ref = PubkyUris.parseDeckManifest(subject.uri.value) ?: return null
        if (ref.authorPubky == session.current()?.identity?.pubky) return null
        // An empty tagger list means the indexer capped it, not that nobody tagged it — only
        // reject when we can actually see the taggers and the author isn't among them.
        if (subject.taggers.isNotEmpty() && ref.authorPubky !in subject.taggers) {
            Log.d(TAG, "verifiedDeck: ${subject.uri.value} not tagged by its author")
            return null
        }
        return deckRepository.fetchRemote(ref.authorPubky, ref.deckId).getOrNull()
    }

    override suspend fun searchPeople(query: String, limit: Int): List<PubkyIdentity> {
        val q = query.trim()
        if (q.length < DiscoveryRepository.MIN_SEARCH_QUERY_LENGTH) return emptyList()
        val me = session.current()?.identity?.pubky

        // Two indexes, one query: a name prefix and a pubky prefix are different lookups, and the
        // box does not ask which one was typed. The pubky lookup is skipped for anything that
        // could not be a key, so a name costs one request rather than two.
        val (byName, byId) = coroutineScope {
            val name = async { nexus.searchUsersByName(q, limit).orEmptyLogging("by_name") }
            val id = async {
                if (!PubkyLinks.isPubkyPrefix(q, NexusClient.MIN_USER_ID_PREFIX)) {
                    emptyList()
                } else {
                    nexus.searchUsersById(q, limit).orEmptyLogging("by_id")
                }
            }
            name.await() to id.await()
        }

        // Names first: someone who typed letters meant a name, and a pubky-prefix hit on the same
        // letters is the weaker reading of the same input.
        val candidates = (byName + byId).distinct().filterNot { it == me }.take(limit)
        val kept = candidates.mapConcurrently { verifiedUser(it, keepUnresolved = true) }.filterNotNull()
        Log.d(TAG, "searchPeople('$q'): ${kept.size} Loopky of ${candidates.size} indexed")
        return kept
    }

    override suspend fun searchDecks(query: String, limit: Int): List<Deck> {
        val q = query.trim()
        if (q.length < DiscoveryRepository.MIN_SEARCH_QUERY_LENGTH) return emptyList()
        val needle = q.lowercase()

        // The sample is what makes a *title* searchable at all; the tag read is what reaches past
        // it, since a deck tagged "spanish" is findable by that label however unpopular it is.
        val (sample, tagged) = coroutineScope {
            val sample = async { searchableDecks() }
            val tagged = async {
                if (!isTagShaped(needle)) {
                    emptyList()
                } else {
                    runSuspendCatching { decksByTagGlobal(Tag(needle), limit) }
                        .onFailure { Log.w(TAG, "searchDecks: tag read failed — ${it.message}") }
                        .getOrElse { emptyList() }
                }
            }
            sample.await() to tagged.await()
        }

        val matches = (sample.filter { it.matches(needle) } + tagged)
            .distinctBy { it.authorPubky + "/" + it.id }
            .sortedByDescending { it.relevanceTo(needle) }
            .take(limit)
        Log.d(TAG, "searchDecks('$q'): ${matches.size} of ${sample.size} sampled + ${tagged.size} tagged")
        return matches
    }

    /**
     * The decks a title search runs against, fetched once per session.
     *
     * The lock is held across the fetch on purpose: it is ~[SEARCH_DECK_SAMPLE] manifest reads, and
     * a second keystroke landing mid-fetch should wait rather than start its own. An empty result is
     * never cached — an indexer down for one query must not leave search dead for the session.
     */
    private suspend fun searchableDecks(): List<Deck> = deckSampleLock.withLock {
        deckSample?.let { return@withLock it }
        val decks = runSuspendCatching {
            decksByTagGlobal(ReservedTags.DECK, DiscoveryRepository.SEARCH_DECK_SAMPLE)
        }
            .onFailure { Log.e(TAG, "searchableDecks: FAILED — ${it.message}", it) }
            .getOrElse { emptyList() }
        if (decks.isNotEmpty()) deckSample = decks
        decks
    }

    /** The global deck sample search matches titles against, or null until first searched. */
    private var deckSample: List<Deck>? = null
    private val deckSampleLock = Mutex()

    private fun <T> Result<List<T>>.orEmptyLogging(what: String): List<T> =
        onFailure { Log.w(TAG, "searchPeople: $what failed — ${it.message}") }.getOrElse { emptyList() }

    override suspend fun loopkyUsers(limit: Int): List<PubkyIdentity> {
        val me = session.current()?.identity?.pubky
        val candidates = directoryCandidates(limit).filterNot { it == me }.take(limit)
        // keepUnresolved, like every other caller: the self-tag has already proved this is a real
        // Loopky account, and a missing `pubky.app/profile.json` is a 404 on a record signing up
        // never had to write. Dropping on it emptied the directory of exactly the accounts it exists
        // to surface. Measured on staging: 4 kept of 10, all six drops a profile 404.
        val kept = candidates
            .mapConcurrently { pubky -> verifiedUser(pubky, keepUnresolved = true) }
            .filterNotNull()
        Log.d(TAG, "loopkyUsers: ${kept.size} verified of ${candidates.size} candidates")
        return kept
    }

    /**
     * The pubkys worth asking about, from three indexer reads at once — see
     * [DiscoveryRepository.loopkyUsers] for why it takes all three.
     *
     * Each source swallows its own failure rather than emptying the union: the profile-tag search is
     * a 404 on an indexer that predates it (#134). Deduped in source order, so a self-tagged account
     * keeps its place ahead of one merely inferred from a deck. The [limit] is spent per source, not
     * across them.
     */
    private suspend fun directoryCandidates(limit: Int): List<String> = coroutineScope {
        val selfTagged = async {
            // Throws where the other tag reads return empty, precisely so this can tell "the
            // indexer has no such query" from "no Loopky users" — the confusion #134 was.
            runSuspendCatching { tagRepository.usersTagged(ReservedTags.USER, limit) }
                .onFailure { Log.w(TAG, "loopkyUsers: profile-tag search unavailable — ${it.message}") }
                .getOrElse { emptyList() }
        }
        val deckOwners = async {
            // Swallowed here, unlike everywhere else this read is made: the union's whole job is
            // that no single source can empty it, and the people strip has the browse strip's
            // error standing beside it already.
            runSuspendCatching {
                tagRepository
                    .taggedSubjects(ReservedTags.DECK, DiscoveryRepository.DIRECTORY_DECK_SAMPLE)
                    // The author is the URI's owner, and parsing it is also what rules out a label
                    // pointed at something that is not a deck manifest at all.
                    .mapNotNull { PubkyUris.parseDeckManifest(it.uri.value)?.authorPubky }
            }
                .onFailure { Log.w(TAG, "loopkyUsers: deck sample unavailable — ${it.message}") }
                .getOrElse { emptyList() }
        }
        val announcers = async { tagRepository.postAuthorsTagged(ReservedTags.DECK) }
        val union = (selfTagged.await() + deckOwners.await() + announcers.await()).distinct()
        Log.d(TAG, "loopkyUsers: ${union.size} candidates from profile tags, decks and posts")
        union
    }

    override suspend fun suggestedPeoplePage(
        seedDecks: List<Deck>,
        limit: Int,
        cursor: Int,
    ): PeoplePage {
        val me = session.current()?.identity?.pubky
        // A failure here must not empty the strip — worst case we suggest someone already followed.
        val followed = runSuspendCatching { following() }.getOrElse { emptyList() }.toSet()
        val roll = candidateRoll(seedDecks)

        val kept = mutableListOf<PubkyIdentity>()
        var index = cursor
        while (kept.size < limit && index < roll.size) {
            // A batch at a time rather than one candidate at a time: each costs a self-tag check
            // and a profile fetch, and the whole point of a cursor here is that those are the
            // expensive part. Asking for exactly what is missing keeps the last batch small.
            val batch = roll.subList(index, minOf(index + limit - kept.size, roll.size))
            index += batch.size
            batch
                .filterNot { it.pubky == me || it.pubky in followed }
                .mapConcurrently { candidate -> resolveCandidate(candidate) }
                .filterNotNullTo(kept)
        }

        Log.d(
            TAG,
            "suggestedPeoplePage: ${kept.size}/$limit, cursor $cursor -> $index of ${roll.size}",
        )
        return PeoplePage(people = kept, nextCursor = index, hasMore = index < roll.size)
    }

    /**
     * Every pubky worth asking about this session, in a stable order a cursor can index.
     *
     * Built once from the three directory reads and then only ever appended to, because a cursor
     * into a list that reorders itself between pages is not a cursor. [seedDecks] authors go on the
     * end as browse turns up more of them, keeping their place behind the directory.
     */
    private suspend fun candidateRoll(seedDecks: List<Deck>): List<Candidate> =
        candidateLock.withLock {
            val roll = candidates
                ?: directoryCandidates(DiscoveryRepository.DIRECTORY_CANDIDATE_LIMIT)
                    .mapTo(mutableListOf()) { Candidate(it, fromDeck = false) }
                    .also { candidates = it }
            val known = roll.mapTo(mutableSetOf()) { it.pubky }
            seedDecks.forEach { deck ->
                if (known.add(deck.authorPubky)) roll += Candidate(deck.authorPubky, fromDeck = true)
            }
            roll.toList()
        }

    /**
     * Turn a candidate into someone worth showing, or null.
     *
     * **[Candidate.fromDeck] skips the self-tag check, and that is the point of tracking it.** A
     * directory entry is only ever a claim by whoever wrote the label, so it has to prove itself;
     * an author whose manifest [decksByTagGlobalPage] has already fetched and parsed has proved it
     * by publishing. Holding them to the self-tag as well drops exactly the people the seed exists
     * to reach — the ones whose `loopky-user` record never got written.
     *
     * Either way an unresolved `profile.json` downgrades to a bare pubky rather than dropping the
     * account: it is a record signing up never had to write.
     */
    private suspend fun resolveCandidate(candidate: Candidate): PubkyIdentity? = when {
        candidate.fromDeck -> identityRepository.fetchProfile(candidate.pubky).getOrNull()
            ?: PubkyIdentity(candidate.pubky, displayName = null, avatarUrl = null, bio = null)
        else -> verifiedUser(candidate.pubky, keepUnresolved = true)
    }

    /** One entry of the people roll, with how it got there. */
    private data class Candidate(val pubky: String, val fromDeck: Boolean)

    /** The candidate order this session's [suggestedPeoplePage] cursors index into. */
    private var candidates: MutableList<Candidate>? = null
    private val candidateLock = Mutex()

    /**
     * Kept only if the account tagged *itself* with [ReservedTags.USER] — tagger and subject being
     * the same is what makes the claim verifiable rather than someone's claim about someone else.
     *
     * [keepUnresolved] returns the account under a bare pubky when its profile does not resolve, for
     * callers already corroborated by the self-tag that would rather show a truncated key than drop
     * a real person.
     *
     * The self-tag answer is cached for the session: a follow list asks it of every candidate and the
     * profile screen asks the same people again moments later.
     */
    private suspend fun verifiedUser(
        pubky: String,
        keepUnresolved: Boolean = false,
    ): PubkyIdentity? {
        val selfTagged = selfTagLock.withLock { selfTagCache[pubky] } ?: run {
            tagRepository.isSelfTagged(pubky, ReservedTags.USER).also { answer ->
                if (!answer) Log.d(TAG, "verifiedUser: $pubky did not self-tag")
                selfTagLock.withLock { selfTagCache[pubky] = answer }
            }
        }
        if (!selfTagged) return null

        val profile = identityRepository.fetchProfile(pubky).getOrNull()
        return when {
            profile != null -> profile
            keepUnresolved -> PubkyIdentity(pubky, displayName = null, avatarUrl = null, bio = null)
            else -> null
        }
    }

    /** Self-tag answers seen this session, keyed by pubky. */
    private val selfTagCache = mutableMapOf<String, Boolean>()
    private val selfTagLock = Mutex()

    /**
     * Followee pubkys out of an already-decoded listing of `pubky://…` urls.
     *
     * Decoded per entry rather than scanned for `/pub/pubky.app/follows/`: a substring scan cannot
     * tell where one url ends, so it cut every id at the next entry's `pubky://` and yielded debris
     * like `friend1","pubky:`. `followUser` seeds the cache optimistically, so that only surfaced on
     * a cold cache — after a restart, when the whole follow feed silently emptied.
     */
    private fun parseFollowees(entries: List<String>): List<String> =
        entries.mapNotNull { url ->
            url.substringAfter(FOLLOWS_MARKER, missingDelimiterValue = "")
                .substringBefore('/')
                .takeIf { it.isNotEmpty() }
        }.distinct()

    companion object {
        private const val TAG = "Loopky/DiscoveryRepo"

        /** The segment a follow record's url carries just before the followee's pubky. */
        private const val FOLLOWS_MARKER = "/pub/pubky.app/follows/"

        /** pubky-app-specs post ids are microsecond timestamps; commonMain's clock is millis. */
        private const val MICROS_PER_MILLI = 1_000L

        /**
         * How many indexer reads one [decksByTagGlobalPage] may spend refilling a page.
         *
         * A corpus where nothing verifies — a stale index, or an author whose homeserver is down —
         * would otherwise walk the whole tag index one window at a time before returning a single
         * deck. Four keeps a short page short instead of turning it into a long one.
         */
        private const val MAX_REFILL_REQUESTS = 4
    }
}

/**
 * How well a deck answers [needle], most specific first: the title someone typed, then the title
 * they half-remembered, then a topic, then a key they were handed.
 */
private fun Deck.relevanceTo(needle: String): Int = when {
    title.lowercase().startsWith(needle) -> TITLE_PREFIX_MATCH
    title.lowercase().contains(needle) -> TITLE_BODY_MATCH
    tags.any { it.value.lowercase().startsWith(needle) } -> TAG_MATCH
    authorPubky.startsWith(needle) -> AUTHOR_MATCH
    else -> NO_MATCH
}

private fun Deck.matches(needle: String): Boolean = relevanceTo(needle) > NO_MATCH

/**
 * Whether [needle] could be a tag label. Tags are single lowercase words, so a phrase is a title
 * search and asking the indexer about it would only cost a round-trip that cannot match.
 */
private fun isTagShaped(needle: String): Boolean = needle.none { it.isWhitespace() }

private const val TITLE_PREFIX_MATCH = 4
private const val TITLE_BODY_MATCH = 3
private const val TAG_MATCH = 2
private const val AUTHOR_MATCH = 1
private const val NO_MATCH = 0
