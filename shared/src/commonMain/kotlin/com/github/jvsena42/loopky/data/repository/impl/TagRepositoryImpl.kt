package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.nexus.NexusResourceSorting
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.PubkyUris
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.TagDto
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.TagLabels
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.serialization.encodeToString

/**
 * Tags use the pubky-app-specs primitive: a record whose body is `{uri, label, created_at}` and
 * whose id is content-derived (`HashId`), written to the *tagger's* homeserver. Nexus indexes
 * these records network-wide, which is what makes [trending] answerable — a single homeserver has
 * no global view.
 *
 * **Every record goes in pubky.app's folder, `/pub/pubky.app/tags/`, whatever it tags.** Since
 * pubky/pubky-nexus#1129 Nexus reads tags only there and in `/pub/social/v1/tags/`, and routes each
 * by its subject: a profile or post joins the user/post graph, a deck manifest becomes a generic
 * resource. `/pub/loopky/tags/`, where deck tags lived from #40 until #616, is skipped for PUT and
 * DEL alike, though the edges indexed from it stay.
 *
 * **A deck tag is written to both folders until production Nexus runs #1129.** The Nexus before it
 * indexes a deck manifest only from `/pub/loopky/tags/` and drops it under pubky.app; the Nexus
 * after it does the opposite. The tag id is the same in both, so both copies name one edge. A
 * session without `/pub/pubky.app/tags/` (a CLI login or MCP connection older than #616) writes the
 * Loopky copy only. Drop the Loopky copy once production has deployed.
 *
 * This couples Loopky's deck discovery to pubky.app: tags on Loopky's own decks have to live in
 * another app's namespace, and the CLI and MCP scopes reach into it (`/pub/pubky.app/tags/:rw`).
 * Nexus was asked to accept `social/v1/tags/` from every user or to index app folders again
 * (https://github.com/pubky/pubky-nexus/pull/1129#issuecomment-7102343880); either would let this
 * move out of pubky.app's folder. A user pubky.app has migrated to `social/v1` needs their tags in
 * `/pub/social/v1/tags/` instead, which is #616 section 2.
 *
 * Labels are folded by [TagLabels] and checked against the spec (1–20 chars, no whitespace) before
 * hashing so the derived id always matches what the indexer validates. The resource path does no
 * sanitizing of its own, so this is the only thing keeping deck labels from fragmenting by case,
 * accent or separator (#479).
 */
class TagRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val nexus: NexusClient,
) : TagRepository {

    override suspend fun putTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runSuspendCatching {
        // On the folded label: `loopky_deck` is not reserved as typed and is once it is stored.
        rejectReserved(Tag(TagLabels.fold(tag.value))).getOrThrow()
        write(subjectUri, tag).getOrThrow()
    }

    override suspend fun removeTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runSuspendCatching {
        rejectReserved(tag).getOrThrow()
        erase(subjectUri, tag).getOrThrow()
    }

    override suspend fun putReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runSuspendCatching {
        requireReserved(tag).getOrThrow()
        write(subjectUri, tag).getOrThrow()
    }

    override suspend fun removeReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runSuspendCatching {
        requireReserved(tag).getOrThrow()
        erase(subjectUri, tag).getOrThrow()
    }

    private suspend fun write(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runSuspendCatching {
        val label = sanitizeLabel(TagLabels.fold(tag.value)).getOrThrow()
        val owner = session.requireSession().identity.pubky
        val tagId = pubky.createTagId(subjectUri.value, label).getOrThrow()
        val body = tagBody(subjectUri, label)
        if (!isSocialSubject(subjectUri)) {
            pubky.putWithSessionRetry(PubkyPaths.loopkyTag(owner, tagId), body, session, revalidator).getOrThrow()
            if (!session.requireSession().canWriteDeckTags) return@runSuspendCatching
        }
        pubky.putWithSessionRetry(PubkyPaths.tag(owner, tagId), body, session, revalidator).getOrThrow()
        Unit
    }

    private suspend fun erase(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runSuspendCatching {
        // Not folded: a record written before #479 is keyed by the label as it was typed, and
        // folding here would derive the id of a record that was never there.
        val label = sanitizeLabel(tag.verbatim()).getOrThrow()
        val owner = session.requireSession().identity.pubky
        val tagId = pubky.createTagId(subjectUri.value, label).getOrThrow()
        val path = PubkyPaths.tag(owner, tagId)
        if (isSocialSubject(subjectUri)) {
            deleteIfPresent(path)
            return@runSuspendCatching
        }
        // A session older than #616 can't touch pubky.app's folder: it clears the Loopky copy only.
        val canWriteIndexed = session.requireSession().canWriteDeckTags
        val removed = canWriteIndexed && deleteIfPresent(path)

        // A deck tag written before #616 lives only under `/pub/loopky/tags/`, where Nexus after #1129
        // skips the DEL and keeps the edge. Writing the record again where it reads tags and deleting
        // it there gives it a DEL it handles; the tag id is the same in every folder.
        val legacyPath = PubkyPaths.loopkyTag(owner, tagId)
        if (deleteIfPresent(legacyPath) && canWriteIndexed && !removed) {
            pubky.putWithSessionRetry(path, tagBody(subjectUri, label), session, revalidator).getOrThrow()
            pubky.deleteWithSessionRetry(path, session, revalidator).getOrThrow()
        }
    }

    /** A record that is not there is the outcome being asked for, so a 404 is not a failure. */
    private suspend fun deleteIfPresent(path: String): Boolean =
        pubky.deleteWithSessionRetry(path, session, revalidator).fold(
            onSuccess = { true },
            onFailure = { err ->
                if (!err.isNotFound()) throw err
                Log.d(TAG, "removeTag: no record at $path")
                false
            },
        )

    override suspend fun trendingDeckTags(sampleSize: Int, limit: Int): Result<List<Tag>> {
        // Another app or a web URL can carry `loopky-deck` too, and its labels must not become topics.
        val resources = nexus.resourcesByTag(ReservedTags.DECK.value, sampleSize)
            .onFailure { Log.w(TAG, "trendingDeckTags: FAILED — ${it.message}") }
            .getOrElse { return Result.failure(it) }
            .filter { PubkyUris.parseDeckManifest(it.details.uri) != null }

        val decksPerLabel = mutableMapOf<String, Int>()
        val taggersPerLabel = mutableMapOf<String, Int>()
        for (resource in resources) {
            resource.tags
                .filterNot { ReservedTags.isReserved(it.label) }
                // One deck counts once per label however many times the indexer lists it.
                .distinctBy { it.label }
                .forEach { details ->
                    decksPerLabel[details.label] = (decksPerLabel[details.label] ?: 0) + 1
                    taggersPerLabel[details.label] =
                        (taggersPerLabel[details.label] ?: 0) + details.taggers_count
                }
        }

        Log.d(TAG, "trendingDeckTags: ${decksPerLabel.size} labels over ${resources.size} decks")

        // Deck count first: that is what makes a label a *topic*, and it stops one heavily-tagged
        // deck from owning the whole chip row. Tagger sum breaks ties, then the label itself so
        // the order is stable — the indexer's own ordering is not, and at this corpus size most
        // ties are 1-vs-1.
        return Result.success(decksPerLabel.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { taggersPerLabel[it.key] ?: 0 }
                    .thenBy { it.key },
            )
            .take(limit)
            .map { Tag(it.key) },
        )
    }

    override suspend fun taggedSubjects(
        tag: Tag,
        limit: Int,
        skip: Int,
        sorting: NexusResourceSorting,
    ): List<TaggedSubject> {
        val label = sanitizeLabel(tag.verbatim()).getOrElse { return emptyList() }
        // Propagated, not swallowed — see the contract. An unreachable indexer must never reach a
        // screen as "nothing published".
        return nexus.resourcesByTag(label, limit, skip, sorting)
            .onFailure { Log.w(TAG, "taggedSubjects('$label'): FAILED — ${it.message}") }
            .getOrThrow()
            .map { resource ->
                // Prefer the count for *this* label; the resource-level count spans every label
                // on the subject, and the per-label entry can be missing if the tag list was cut.
                val details = resource.tags.firstOrNull { it.label == label }
                TaggedSubject(
                    uri = PubkyUri(resource.details.uri),
                    taggers = details?.taggers.orEmpty(),
                    taggersCount = details?.taggers_count ?: resource.taggers_count,
                )
            }
    }

    override suspend fun usersTagged(tag: Tag, limit: Int): List<String> {
        val label = sanitizeLabel(tag.verbatim()).getOrElse { return emptyList() }
        // Propagated, not swallowed: a 404 here means the indexer predates the endpoint, and the
        // caller falls back to the deck-derived sources on it. See the contract.
        return nexus.usersByProfileTag(label, limit)
            .onFailure { Log.w(TAG, "usersTagged('$label'): FAILED — ${it.message}") }
            .getOrThrow()
    }

    override suspend fun postAuthorsTagged(tag: Tag, limit: Int): List<String> {
        val label = sanitizeLabel(tag.verbatim()).getOrElse { return emptyList() }
        return nexus.postAuthorsByTag(label, limit)
            .onFailure { Log.w(TAG, "postAuthorsTagged('$label'): FAILED — ${it.message}") }
            .getOrElse { emptyList() }
    }

    override suspend fun isSelfTagged(pubky: String, tag: Tag): Boolean {
        val label = sanitizeLabel(tag.verbatim()).getOrElse { return false }
        return pubky in nexus.userTaggers(pubky, label).getOrElse { emptyList() }
    }

    override suspend fun taggerCounts(subjectUri: PubkyUri): Map<Tag, Int> =
        nexus.resourceByUri(subjectUri.value)
            // A 404 here just means nobody has tagged it yet, which is not worth an error log.
            .getOrElse { return emptyMap() }
            .tags
            .associate { Tag(it.label) to it.taggers_count }

    private companion object {
        const val TAG = "Loopky/TagRepo"
    }
}

private fun rejectReserved(tag: Tag): Result<Unit> =
    if (ReservedTags.isReserved(tag)) {
        Result.failure(IllegalArgumentException("'${tag.value}' is reserved — see ReservedTags"))
    } else {
        Result.success(Unit)
    }

private fun requireReserved(tag: Tag): Result<Unit> =
    if (tag in ReservedTags.ALL) {
        Result.success(Unit)
    } else {
        Result.failure(
            IllegalArgumentException("'${tag.value}' is not one of Loopky's index labels"),
        )
    }

private fun tagBody(subjectUri: PubkyUri, label: String): String =
    loopkyJson.encodeToString(TagDto(uri = subjectUri.value, label = label, created_at = epochMillis()))

/** A pubky.app profile or post: never tagged under `/pub/loopky/tags/`, so there is no old record to clear. */
private fun isSocialSubject(subjectUri: PubkyUri): Boolean =
    PubkyUris.isProfile(subjectUri.value) || PubkyUris.isPost(subjectUri.value)

/**
 * The label as it was typed, for anything that has to address a record that already exists: a
 * read or a delete of a pre-#479 `café` must ask for `café`, since the indexer matches byte for byte.
 */
private fun Tag.verbatim(): String = value.trim().lowercase()

/** pubky-app-specs tag label rules: 1–20 chars, no whitespace. */
private fun sanitizeLabel(label: String): Result<String> =
    when {
        label.isEmpty() ->
            Result.failure(IllegalArgumentException("Tag label must not be empty"))

        TagLabels.lengthOf(label) > TagLabels.MAX_LENGTH ->
            Result.failure(
                IllegalArgumentException("Tag label must be at most ${TagLabels.MAX_LENGTH} chars"),
            )

        label.any { it.isWhitespace() } ->
            Result.failure(
                IllegalArgumentException("Tag label must not contain whitespace"),
            )

        else -> Result.success(label)
    }
