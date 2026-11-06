package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.LoopkyPubkyAppRecords
import com.github.jvsena42.loopky.data.pubky.PostDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.TagDto
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.util.runSuspendCatching

/**
 * Reads what an account holds under `/pub/pubky.app/`, judged by [LoopkyPubkyAppRecords].
 * Public reads only: nothing here needs a session.
 */
internal class PubkyAppFootprintReader(private val pubky: PubkyClient) {

    /**
     * The posts Loopky published for [owner], each with the tag records on it. A post or tag that
     * cannot be read is left out, so a failed read never turns into a delete.
     */
    suspend fun loopkyPosts(owner: String): List<LoopkyPost> {
        val tagsOnOwnPosts = readTags(owner).records.filter { it.tag.uri.startsWith(PubkyPaths.postsRoot(owner)) }
        val labels = tagsOnOwnPosts.groupBy({ it.tag.uri }, { it.tag.label.trim().lowercase() })
        val tagPaths = tagsOnOwnPosts.groupBy({ it.tag.uri }, { it.path })
        return pubky.listAllEntriesOrEmpty(PubkyPaths.postsRoot(owner))
            .filterNot { it.endsWith("/") }
            .mapConcurrently { path ->
                val post = readPost(path).getOrNull() ?: return@mapConcurrently null
                val isLoopkys = LoopkyPubkyAppRecords.isLoopkyPost(owner, post, labels[path].orEmpty().toSet())
                if (isLoopkys) LoopkyPost(path, tagPaths[path].orEmpty()) else null
            }
            .filterNotNull()
    }

    private suspend fun readTags(owner: String): TagRead {
        val listing = pubky.listAllEntriesPartial(tagsRoot(owner))
        val reads = listing.entries
            .filterNot { it.endsWith("/") }
            .mapConcurrently { path -> path to readTag(path) }
        return TagRead(
            records = reads.mapNotNull { (path, read) -> read.getOrNull()?.let { TagRecord(path, it) } },
            complete = listing.failure == null && !listing.truncated && reads.all { it.second.isSuccess },
        )
    }

    private suspend fun readTag(path: String): Result<TagDto> = runSuspendCatching {
        loopkyJson.decodeFromString<TagDto>(pubky.get(path).getOrThrow())
    }

    private suspend fun readPost(path: String): Result<PostDto> = runSuspendCatching {
        loopkyJson.decodeFromString<PostDto>(pubky.get(path).getOrThrow())
    }

    private fun tagsRoot(owner: String): String = PubkyPaths.tag(owner, "")

    private class TagRecord(val path: String, val tag: TagDto)

    /** [complete] is false when any listing page or record could not be read. */
    private class TagRead(val records: List<TagRecord>, val complete: Boolean)
}

/** A post Loopky published, at [path], and the tag records on it that go with it. */
internal data class LoopkyPost(val path: String, val tagPaths: List<String>)
