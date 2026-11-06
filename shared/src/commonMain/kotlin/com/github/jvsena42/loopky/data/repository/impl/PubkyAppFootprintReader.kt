package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.LoopkyPubkyAppRecords
import com.github.jvsena42.loopky.data.pubky.PostDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.TagDto
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.domain.model.PubkyAppFootprint
import com.github.jvsena42.loopky.util.runSuspendCatching

/**
 * Reads what an account holds under `/pub/pubky.app/`, judged by [LoopkyPubkyAppRecords].
 * Public reads only: nothing here needs a session.
 */
internal class PubkyAppFootprintReader(private val pubky: PubkyClient) {

    /**
     * Whether anything but Loopky has written to [owner]'s account. Stops at the first record
     * that proves another app did. Any failed read ends in [PubkyAppFootprint.Unknown] unless such
     * a proof was found anyway; a record that reads but does not parse is another app's.
     */
    suspend fun classify(owner: String): PubkyAppFootprint = Classification(owner).run()

    /** One [classify] run; [incomplete] collects the failed reads that did not stop it. */
    private inner class Classification(private val owner: String) {
        private var incomplete = false
        private val ownPosts = PubkyPaths.postsRoot(owner)

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

        /** The records under pubky.app, unless the listings already decide the answer. */
        private suspend fun listRecords(): Step<List<String>> {
            val namespaces = firstLevel(pubRoot(owner)) ?: return Step.Stop(PubkyAppFootprint.Unknown)
            if (namespaces.entries.any { it !in OWN_NAMESPACES }) return Step.Stop(PubkyAppFootprint.Direct)
            val records = recordsUnder(pubkyAppRoot(owner)) ?: return Step.Stop(PubkyAppFootprint.Unknown)
            incomplete = !namespaces.complete || !records.complete
            val names = records.entries.map { firstSegment(pubkyAppRoot(owner), it) }
            if (names.any { it !in LoopkyPubkyAppRecords.ENTRIES }) return Step.Stop(PubkyAppFootprint.Direct)
            return Step.Next(records.entries)
        }

        private suspend fun profileIsAnotherApps(records: List<String>): Boolean {
            val profile = PubkyPaths.profile(owner)
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
            val tags = records.filter { it.startsWith(tagsRoot(owner)) }.mapConcurrently { readTag(it) }
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
                val post = readPost(path).getOrElse { return@judgeAll it.asJudgement() }
                when {
                    LoopkyPubkyAppRecords.isLoopkyPost(owner, post, labels[path].orEmpty()) -> Judgement.Loopkys
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

    /** Every record under [owner]'s `/pub/pubky.app/`. Throws unless the listing read to the end. */
    suspend fun pubkyAppRecords(owner: String): List<String> {
        val records = checkNotNull(recordsUnder(pubkyAppRoot(owner))) { "pubky.app could not be listed" }
        check(records.complete) { "pubky.app listing is incomplete" }
        return records.entries
    }

    /**
     * The posts Loopky published for [owner], each with the tag records on it. A post or tag that
     * cannot be read is left out, so a failed read never turns into a delete.
     */
    suspend fun loopkyPosts(owner: String): List<LoopkyPost> {
        val ownPosts = PubkyPaths.postsRoot(owner)
        val tagsOnOwnPosts = pubky.listAllEntriesOrEmpty(tagsRoot(owner))
            .filterNot { it.endsWith("/") }
            .mapConcurrently { path -> readTag(path).getOrNull()?.let { path to it } }
            .filterNotNull()
            .filter { (_, tag) -> tag.uri.startsWith(ownPosts) }
        val labels = tagsOnOwnPosts.groupBy({ it.second.uri }, { it.second.label.trim().lowercase() })
        val tagPaths = tagsOnOwnPosts.groupBy({ it.second.uri }, { it.first })
        return pubky.listAllEntriesOrEmpty(ownPosts)
            .filterNot { it.endsWith("/") }
            .mapConcurrently { path ->
                val post = readPost(path).getOrNull() ?: return@mapConcurrently null
                val isLoopkys = LoopkyPubkyAppRecords.isLoopkyPost(owner, post, labels[path].orEmpty().toSet())
                if (isLoopkys) LoopkyPost(path, tagPaths[path].orEmpty()) else null
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

    private suspend fun readPost(path: String): Result<PostDto> = read(path) { loopkyJson.decodeFromString<PostDto>(it) }

    /** A failed fetch comes back as [Unreachable]; a body that does not parse as the parse error. */
    private suspend fun <T> read(path: String, parse: (String) -> T): Result<T> {
        val body = runSuspendCatching { pubky.get(path).getOrThrow() }
            .getOrElse { return Result.failure(Unreachable(it)) }
        return runCatching { parse(body) }
    }

    private fun firstSegment(prefix: String, entry: String): String {
        val rest = entry.removePrefix(prefix)
        val slash = rest.indexOf('/')
        return if (slash == -1) rest else rest.substring(0, slash + 1)
    }

    private fun pubRoot(owner: String) = "pubky://$owner/pub/"

    private fun pubkyAppRoot(owner: String) = PubkyPaths.profile(owner).removeSuffix("profile.json")

    private fun tagsRoot(owner: String): String = PubkyPaths.tag(owner, "")

    private enum class Judgement { Loopkys, NotLoopkys, Unreadable }

    private class Listing(val entries: List<String>, val complete: Boolean)

    private sealed interface Step<out T> {
        data class Next<T>(val value: T) : Step<T>
        data class Stop(val footprint: PubkyAppFootprint) : Step<Nothing>
    }

    private class Unreachable(cause: Throwable) : RuntimeException(cause.message, cause)

    private companion object {
        val OWN_NAMESPACES = setOf("loopky/", "pubky.app/")

        /** Reads in flight while classifying; small enough that a proof stops the rest early. */
        const val BATCH = 16
    }
}

/** A post Loopky published, at [path], and the tag records on it that go with it. */
internal data class LoopkyPost(val path: String, val tagPaths: List<String>)
