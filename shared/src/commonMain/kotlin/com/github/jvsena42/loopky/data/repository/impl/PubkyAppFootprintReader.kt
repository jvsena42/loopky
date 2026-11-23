package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.LoopkyPubkyAppRecords
import com.github.jvsena42.loopky.data.pubky.PostDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.SocialLayout
import com.github.jvsena42.loopky.data.pubky.SocialPostDto
import com.github.jvsena42.loopky.data.pubky.TagDto
import com.github.jvsena42.loopky.data.pubky.asLegacy
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.domain.model.PubkyAppFootprint
import com.github.jvsena42.loopky.util.runSuspendCatching

/**
 * Reads what an account holds in the social layouts, `/pub/pubky.app/` and `/pub/social/v1/`
 * ([SocialLayout]), judged by [LoopkyPubkyAppRecords] the same way in both (#616). Public reads
 * only: nothing here needs a session, and a migrated user's private mutes are the eraser's.
 */
internal class PubkyAppFootprintReader(private val pubky: PubkyClient) {

    /**
     * Whether anything but Loopky has written to [owner]'s account. Stops at the first record
     * that proves another app did. Any failed read ends in [PubkyAppFootprint.Unknown] unless such
     * a proof was found anyway; a record that reads but does not parse is another app's.
     */
    suspend fun classify(owner: String): PubkyAppFootprint {
        val layouts = when (val found = layoutsIn(owner)) {
            is Step.Stop -> return found.footprint
            is Step.Next -> found.value
        }
        val footprints = layouts.map { Classification(owner, it.layout, it.complete).run() }
        return when {
            PubkyAppFootprint.Direct in footprints -> PubkyAppFootprint.Direct
            PubkyAppFootprint.Unknown in footprints -> PubkyAppFootprint.Unknown
            PubkyAppFootprint.LoopkyOnly in footprints -> PubkyAppFootprint.LoopkyOnly
            else -> PubkyAppFootprint.Empty
        }
    }

    /**
     * The social layouts [owner] has records in, unless the top of `/pub/` already decides: any
     * namespace but Loopky's and the two social ones is another app, and so is anything under
     * `/pub/social/` outside `v1/`.
     */
    private suspend fun layoutsIn(owner: String): Step<List<FoundLayout>> {
        val namespaces = firstLevel(pubRoot(owner)) ?: return Step.Stop(PubkyAppFootprint.Unknown)
        if (namespaces.entries.any { it !in OWN_NAMESPACES }) return Step.Stop(PubkyAppFootprint.Direct)
        val found = mutableListOf(FoundLayout(SocialLayout.Legacy, namespaces.complete))
        if (SOCIAL_NAMESPACE in namespaces.entries) {
            val epochs = firstLevel(socialRoot(owner)) ?: return Step.Stop(PubkyAppFootprint.Unknown)
            if (epochs.entries.any { it != SOCIAL_V1 }) return Step.Stop(PubkyAppFootprint.Direct)
            found += FoundLayout(SocialLayout.SocialV1, namespaces.complete && epochs.complete)
        }
        return Step.Next(found)
    }

    private class FoundLayout(val layout: SocialLayout, val complete: Boolean)

    /** One layout of one [classify] run; [incomplete] collects the failed reads that did not stop it. */
    private inner class Classification(
        private val owner: String,
        private val layout: SocialLayout,
        namespacesComplete: Boolean,
    ) {
        private var incomplete = !namespacesComplete
        private val ownPosts = layout.postsRoot(owner)

        suspend fun run(): PubkyAppFootprint {
            val records = when (val listed = listRecords()) {
                is Step.Stop -> return listed.footprint
                is Step.Next -> listed.value
            }
            if (records.isEmpty()) return if (incomplete) PubkyAppFootprint.Unknown else PubkyAppFootprint.Empty
            if (profileIsAnotherApps(records)) return PubkyAppFootprint.Direct
            val labels = labelsOnOwnPosts(records) ?: return PubkyAppFootprint.Direct
            return when (judgePosts(records, labels)) {
                Judgement.NotLoopkys -> PubkyAppFootprint.Direct
                Judgement.Unreadable -> PubkyAppFootprint.Unknown
                Judgement.Loopkys -> if (incomplete) PubkyAppFootprint.Unknown else PubkyAppFootprint.LoopkyOnly
            }
        }

        /** The records in this layout, unless the listing already decides the answer. */
        private suspend fun listRecords(): Step<List<String>> {
            val root = layoutRoot(owner, layout)
            val records = recordsUnder(root) ?: return Step.Stop(PubkyAppFootprint.Unknown)
            incomplete = incomplete || !records.complete
            val names = records.entries.map { firstSegment(root, it) }
            if (names.any { it !in LoopkyPubkyAppRecords.ENTRIES }) return Step.Stop(PubkyAppFootprint.Direct)
            return Step.Next(records.entries)
        }

        private suspend fun profileIsAnotherApps(records: List<String>): Boolean {
            val profile = layout.profile(owner)
            if (profile !in records) return false
            val body = runSuspendCatching { pubky.get(profile).getOrThrow() }.getOrElse {
                incomplete = true
                return false
            }
            return !LoopkyPubkyAppRecords.isLoopkyProfile(body)
        }

        /**
         * The labels on the user's own posts, or null when a tag record proves another app wrote
         * it. Read whole rather than stopped early: the labels are needed to judge the posts.
         */
        private suspend fun labelsOnOwnPosts(records: List<String>): Map<String, Set<String>>? {
            val tags = records.filter { it.startsWith(layout.tagsRoot(owner)) }.mapConcurrently { readTag(it) }
            if (tags.any { it.exceptionOrNull()?.let { err -> err !is Unreachable } == true }) return null
            if (tags.any { it.isFailure }) {
                incomplete = true
                tagsIncomplete = true
            }
            val read = tags.mapNotNull { it.getOrNull() }
            if (read.any { !isLoopkySubject(it.uri) }) return null
            return read.filter { it.uri.startsWith(ownPosts) }
                .groupBy({ it.uri }, { it.label.trim().lowercase() })
                .mapValues { it.value.toSet() }
        }

        private var tagsIncomplete = false

        private fun isLoopkySubject(uri: String): Boolean =
            uri.startsWith(ownPosts) ||
                LoopkyPubkyAppRecords.isProfileUri(uri) ||
                LoopkyPubkyAppRecords.isLoopkyNamespaceUri(uri)

        private suspend fun judgePosts(records: List<String>, labels: Map<String, Set<String>>): Judgement =
            judgeAll(records.filter { it.startsWith(ownPosts) }) { path ->
                val post = readPost(layout, path).getOrElse { return@judgeAll it.asJudgement() }
                when {
                    LoopkyPubkyAppRecords.isLoopkyPost(owner, post, labels[postReference(layout, path)].orEmpty()) ->
                        Judgement.Loopkys
                    // With a label unread, a post that only misses a label may be Loopky's after all.
                    tagsIncomplete &&
                        LoopkyPubkyAppRecords.isLoopkyPost(owner, post, LoopkyPubkyAppRecords.POST_LABELS) ->
                        Judgement.Unreadable
                    else -> Judgement.NotLoopkys
                }
            }

        /**
         * [judgeEach] over [paths] in batches, stopping at the first batch holding a
         * [Judgement.NotLoopkys]. Otherwise [Judgement.Unreadable] if any read failed.
         */
        private suspend fun judgeAll(paths: List<String>, judgeEach: suspend (String) -> Judgement): Judgement {
            var unreadable = false
            for (batch in paths.chunked(BATCH)) {
                val judgements = batch.mapConcurrently { judgeEach(it) }
                if (Judgement.NotLoopkys in judgements) return Judgement.NotLoopkys
                if (Judgement.Unreadable in judgements) unreadable = true
            }
            return if (unreadable) Judgement.Unreadable else Judgement.Loopkys
        }

        private fun Throwable.asJudgement(): Judgement =
            if (this is Unreachable) Judgement.Unreadable else Judgement.NotLoopkys
    }

    /** Every record in [owner]'s social layouts. Throws unless every listing read to the end. */
    suspend fun pubkyAppRecords(owner: String): List<String> =
        LAYOUTS.flatMap { recordsIn(owner, it) }

    private suspend fun recordsIn(owner: String, layout: SocialLayout): List<String> {
        val records = checkNotNull(recordsUnder(layoutRoot(owner, layout))) { "${layout.epoch} could not be listed" }
        check(records.complete) { "${layout.epoch} listing is incomplete" }
        return records.entries
    }

    /**
     * Every record in [owner]'s social layouts, judged one by one, for the delete itself. Throws
     * unless every listing read to the end. A record that cannot be read is never Loopky's.
     */
    suspend fun judgedPubkyAppRecords(owner: String): JudgedRecords {
        val judged = LAYOUTS.map { judgeLayout(owner, it) }
        return JudgedRecords(
            loopkys = judged.flatMap { it.loopkys },
            foreign = judged.flatMap { it.foreign },
            unreadable = judged.flatMap { it.unreadable },
        )
    }

    private suspend fun judgeLayout(owner: String, layout: SocialLayout): JudgedRecords {
        val records = recordsIn(owner, layout)
        val ownPosts = layout.postsRoot(owner)
        val postFiles = records.filter { it.startsWith(ownPosts) }.toSet()
        val postRefs = postFiles.map { postReference(layout, it) }.toSet()
        val tags = records.filter { it.startsWith(layout.tagsRoot(owner)) }.mapConcurrently { it to readTag(it) }.toMap()
        val labels = tags.values.mapNotNull { it.getOrNull() }
            .filter { it.uri.startsWith(ownPosts) }
            .groupBy({ it.uri }, { it.label.trim().lowercase() })
        val posts = postFiles.toList().mapConcurrently { it to readPost(layout, it) }.toMap()
        val loopkyPostFiles = posts.filter { (path, post) ->
            val onIt = labels[postReference(layout, path)].orEmpty().toSet()
            post.getOrNull()?.let { LoopkyPubkyAppRecords.isLoopkyPost(owner, it, onIt) } == true
        }.keys
        val loopkyPostRefs = loopkyPostFiles.map { postReference(layout, it) }.toSet()
        val profile = layout.profile(owner)
        val profileRead = if (profile in records) read(profile) { it } else null

        val judged = records.associateWith { path ->
            when {
                path == profile -> requireNotNull(profileRead).fold(
                    onSuccess = { if (LoopkyPubkyAppRecords.isLoopkyProfile(it)) Verdict.Loopkys else Verdict.Foreign },
                    onFailure = { it.verdict() },
                )
                path.startsWith(layout.followsRoot(owner)) || path.startsWith(layout.mutesRoot(owner)) ->
                    Verdict.Loopkys
                path in tags -> tags.getValue(path).fold(
                    onSuccess = {
                        val isLoopkys = LoopkyPubkyAppRecords.isLoopkyLabel(it.uri, ownPosts, loopkyPostRefs, postRefs)
                        if (isLoopkys) Verdict.Loopkys else Verdict.Foreign
                    },
                    onFailure = { it.verdict() },
                )
                path in loopkyPostFiles -> Verdict.Loopkys
                path in posts -> posts.getValue(path).exceptionOrNull()?.verdict() ?: Verdict.Foreign
                else -> Verdict.Foreign
            }
        }
        fun with(verdict: Verdict) = judged.filterValues { it == verdict }.keys.toList()
        return JudgedRecords(loopkys = with(Verdict.Loopkys), foreign = with(Verdict.Foreign), unreadable = with(Verdict.Unreadable))
    }

    private fun Throwable.verdict(): Verdict = if (this is Unreachable) Verdict.Unreadable else Verdict.Foreign

    private enum class Verdict { Loopkys, Foreign, Unreadable }

    /**
     * The posts Loopky published for [owner], each with the tag records on it. A post or tag that
     * cannot be read is left out, so a failed read never turns into a delete.
     */
    suspend fun loopkyPosts(owner: String): List<LoopkyPost> = LAYOUTS.flatMap { loopkyPostsIn(owner, it) }

    private suspend fun loopkyPostsIn(owner: String, layout: SocialLayout): List<LoopkyPost> {
        val ownPosts = layout.postsRoot(owner)
        val tagsOnOwnPosts = pubky.listAllEntriesOrEmpty(layout.tagsRoot(owner))
            .filterNot { it.endsWith("/") }
            .mapConcurrently { path -> readTag(path).getOrNull()?.let { path to it } }
            .filterNotNull()
            .filter { (_, tag) -> tag.uri.startsWith(ownPosts) }
        val labels = tagsOnOwnPosts.groupBy({ it.second.uri }, { it.second.label.trim().lowercase() })
        val tagPaths = tagsOnOwnPosts.groupBy({ it.second.uri }, { it.first })
        return pubky.listAllEntriesOrEmpty(ownPosts)
            .filterNot { it.endsWith("/") }
            .mapConcurrently { path ->
                val post = readPost(layout, path).getOrNull() ?: return@mapConcurrently null
                val ref = postReference(layout, path)
                val isLoopkys = LoopkyPubkyAppRecords.isLoopkyPost(owner, post, labels[ref].orEmpty().toSet())
                if (isLoopkys) LoopkyPost(path, tagPaths[ref].orEmpty()) else null
            }
            .filterNotNull()
    }

    /** Every record under [prefix], or null when the first page could not be read. A 404 is empty. */
    private suspend fun recordsUnder(prefix: String): Listing? {
        val listing = pubky.listAllEntriesPartial(prefix)
        val failure = listing.failure
        if (failure != null && failure.isNotFound()) return Listing(emptyList(), complete = true)
        if (failure != null && listing.entries.isEmpty()) return null
        return Listing(
            entries = listing.entries.filterNot { it.endsWith("/") }.distinct(),
            complete = failure == null && !listing.truncated,
        )
    }

    /**
     * The first-level names under [prefix], `dir/` or `file`. Collapsed here rather than trusting
     * `shallow`, which a homeserver may ignore.
     */
    private suspend fun firstLevel(prefix: String): Listing? {
        val listing = pubky.listAllEntriesPartial(prefix, shallow = true)
        val failure = listing.failure
        if (failure != null && failure.isNotFound()) return Listing(emptyList(), complete = true)
        if (failure != null && listing.entries.isEmpty()) return null
        return Listing(
            entries = listing.entries.map { firstSegment(prefix, it) }.filter { it.isNotEmpty() }.distinct(),
            complete = failure == null && !listing.truncated,
        )
    }

    private suspend fun readTag(path: String): Result<TagDto> = read(path) { loopkyJson.decodeFromString<TagDto>(it) }

    /** A v1 post is read into the v0 shape, so one set of rules judges both. */
    private suspend fun readPost(layout: SocialLayout, path: String): Result<PostDto> = read(path) { body ->
        when (layout) {
            SocialLayout.Legacy -> loopkyJson.decodeFromString<PostDto>(body)
            SocialLayout.SocialV1 -> loopkyJson.decodeFromString<SocialPostDto>(body).asLegacy()
        }
    }

    /** A failed fetch comes back as [Unreachable]; a body that does not parse as the parse error. */
    private suspend fun <T> read(path: String, parse: (String) -> T): Result<T> {
        val body = runSuspendCatching { pubky.get(path).getOrThrow() }
            .getOrElse { return Result.failure(Unreachable(it)) }
        return runCatching { parse(body) }
    }

    private enum class Judgement { Loopkys, NotLoopkys, Unreadable }

    private class Listing(val entries: List<String>, val complete: Boolean)

    private sealed interface Step<out T> {
        data class Next<T>(val value: T) : Step<T>
        data class Stop(val footprint: PubkyAppFootprint) : Step<Nothing>
    }

    private class Unreachable(cause: Throwable) : RuntimeException(cause.message, cause)

    private companion object {
        const val SOCIAL_NAMESPACE = "social/"
        const val SOCIAL_V1 = "v1/"
        val OWN_NAMESPACES = setOf("loopky/", "pubky.app/", SOCIAL_NAMESPACE)
        val LAYOUTS = listOf(SocialLayout.Legacy, SocialLayout.SocialV1)

        /** Reads in flight while classifying; small enough that a proof stops the rest early. */
        const val BATCH = 16
    }
}

/** [JudgedRecords] splits a listing by whether Loopky wrote each record. */
internal data class JudgedRecords(val loopkys: List<String>, val foreign: List<String>, val unreadable: List<String>)

/** A post Loopky published, at [path], and the tag records on it that go with it. */
internal data class LoopkyPost(val path: String, val tagPaths: List<String>)

/** The address tags name a post by: a v1 version file `posts/{id}/{editId}.json` is `posts/{id}`. */
private fun postReference(layout: SocialLayout, path: String): String = when (layout) {
    SocialLayout.Legacy -> path
    SocialLayout.SocialV1 -> path.substringBeforeLast('/')
}

private fun firstSegment(prefix: String, entry: String): String {
    val rest = entry.removePrefix(prefix)
    val slash = rest.indexOf('/')
    return if (slash == -1) rest else rest.substring(0, slash + 1)
}

private fun pubRoot(owner: String) = "pubky://$owner/pub/"

private fun socialRoot(owner: String) = "pubky://$owner/pub/social/"

private fun layoutRoot(owner: String, layout: SocialLayout) = layout.profile(owner).removeSuffix("profile.json")
