package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.ORD_STRIDE
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.domain.model.ordForIndex

/**
 * Pure chunk-layout maths shared by `DeckRepositoryImpl` and `CardRepositoryImpl`.
 *
 * The homeserver stores cards as `cards/{n}.json`, each holding up to [CHUNK_SIZE] cards, with the
 * manifest carrying only `{n, count, updated_at}` per chunk. Membership is the union of the chunks —
 * there is no separate card index to keep in sync, which is what used to let a card edit and the
 * manifest drift apart.
 */
internal object CardChunking {

    /**
     * Split cards into chunk-sized batches in study order, assigning a fresh sparse `ord` per
     * position. Used by publish, where the whole card set is being (re)written anyway.
     */
    fun chunk(cards: List<Card>): List<List<Card>> =
        cards.mapIndexed { index, card -> card.copy(ord = ordForIndex(index)) }
            .chunked(CHUNK_SIZE)

    /**
     * Chunk metadata for [chunks], stamped with [updatedAt]. Every chunk gets the same timestamp on a
     * full publish; single-chunk writes bump only their own entry, which is what lets a follower
     * re-fetch one chunk instead of the whole deck.
     */
    fun metaFor(chunks: List<List<Card>>, updatedAt: Long): List<ChunkMeta> =
        chunks.mapIndexed { n, batch -> ChunkMeta(n = n, count = batch.size, updatedAt = updatedAt) }

    /**
     * The chunk a new card should be appended to: the last one with room, or a new trailing chunk.
     *
     * Sequential-append rather than hash-partitioning on card id. Hashing would be stable under every
     * mutation, but it produces [CHUNK_SIZE]-many near-empty records for a 50-card deck; the holes
     * sequential leaves are folded away by [mergeTarget].
     */
    fun appendTarget(chunks: List<ChunkMeta>): Int {
        val last = chunks.maxByOrNull { it.n } ?: return 0
        return if (last.count < CHUNK_SIZE) last.n else last.n + 1
    }

    /**
     * How a batch of new cards lands: which chunk numbers get which cards, tail chunk first.
     *
     * Adding cards through `upsertCard` in a loop costs a chunk write **plus** a whole-manifest
     * read-modify-write each time; planning the batch lets the caller describe the lot in a single
     * manifest patch.
     *
     * Ords continue from [ordFloor] rather than restarting per chunk, because study order is the
     * card's `ord` across the whole deck — numbering each chunk from zero would interleave the
     * appended cards with the ones already there.
     *
     * [tail] is [firstChunk]'s current contents, empty when it is a chunk that does not exist yet.
     */
    fun planAppend(
        cards: List<Card>,
        firstChunk: Int,
        tail: List<Card>,
        ordFloor: Long,
    ): List<Pair<Int, List<Card>>> {
        var ord = ordFloor
        val ordered = cards.map { card ->
            ord += ORD_STRIDE
            card.copy(ord = ord)
        }
        val room = (CHUNK_SIZE - tail.size).coerceAtLeast(0)
        val head = firstChunk to (tail + ordered.take(room)).inStudyOrder()
        val rest = ordered.drop(room).chunked(CHUNK_SIZE)
            .mapIndexed { index, batch -> (firstChunk + 1 + index) to batch }
        return listOf(head) + rest
    }

    /**
     * Apply a single chunk's new contents to the manifest's chunk list.
     *
     * Deleting a card leaves the chunk short rather than resequencing every later card — closing the
     * hole in place would rewrite every following chunk, the write amplification the layout exists to
     * remove. Holes are reclaimed off the critical path by the compaction pass (#51). A chunk emptied
     * completely is dropped from the list; the record itself is deleted by the caller.
     */
    fun withChunk(chunks: List<ChunkMeta>, n: Int, count: Int, updatedAt: Long): List<ChunkMeta> {
        val without = chunks.filterNot { it.n == n }
        return if (count == 0) {
            without.sortedBy { it.n }
        } else {
            (without + ChunkMeta(n = n, count = count, updatedAt = updatedAt)).sortedBy { it.n }
        }
    }

    /**
     * The two chunks a compaction pass should fold together, as `(into, from)`, or null when there is
     * no hole worth closing.
     *
     * Neighbours **in the sorted chunk list**, not numerically adjacent `n`s: a merge leaves a gap in
     * the numbering, and everything downstream reads the list in `n` order rather than assuming it is
     * contiguous. Folding the pair keeps study order because nothing sorts between them.
     *
     * The criterion is "the two records' cards fit in one", so every merge removes exactly one record
     * and the pass converges. Merging up to a full [CHUNK_SIZE] is what stops it thrashing — a chunk
     * left at 99 cannot pair with anything but an almost-empty neighbour.
     */
    fun mergeTarget(chunks: List<ChunkMeta>): Pair<Int, Int>? =
        chunks.sortedBy { it.n }
            .zipWithNext()
            .firstOrNull { (into, from) -> into.count + from.count <= CHUNK_SIZE }
            ?.let { (into, from) -> into.n to from.n }

    /**
     * Whether the chunk table is sparse enough to be worth compacting. Answered from the manifest
     * alone, so asking costs no requests — which is the point: it is checked after every card delete
     * and on every deck listing.
     */
    fun isSparse(chunks: List<ChunkMeta>): Boolean = mergeTarget(chunks) != null

    /**
     * Cards held per slot allocated, in `0f..1f`. `1f` for a deck with no chunks, so callers do not
     * have to special-case an empty deck.
     *
     * Density is what deletes cost: holes never break correctness, but a deck that imported 20k and
     * deleted 15k reads like a 20k-card one, because the request count follows the chunk table.
     */
    fun density(chunks: List<ChunkMeta>): Float {
        if (chunks.isEmpty()) return 1f
        return cardCount(chunks).toFloat() / (chunks.size * CHUNK_SIZE)
    }

    /**
     * Card total derived from the chunk counts. Never taken from a list length: with holes, the
     * only place the true count survives is the per-chunk counts.
     */
    fun cardCount(chunks: List<ChunkMeta>): Int = chunks.sumOf { it.count }

    /**
     * Where study position [index] falls in [chunks] — which record holds it, and at what offset.
     * Derived from the per-chunk counts rather than any card's `ord`, so locating position 12,345 of a
     * 20k-card deck is arithmetic over the manifest, not a scan.
     *
     * [excluding] names a chunk whose count is one short of what the manifest says — the chunk a card
     * is being moved *out of*. A move target is expressed over the deck **without** that card, the way
     * `List.add(index)` on an already-shortened list lands at `index`; without this, moving a card
     * forwards lands it one place short.
     *
     * An index past the end resolves to the tail, so "move to the end" is expressible.
     */
    fun positionAt(chunks: List<ChunkMeta>, index: Int, excluding: Int? = null): ChunkPosition {
        val ordered = chunks.sortedBy { it.n }
        if (ordered.isEmpty()) return ChunkPosition(chunk = 0, offset = 0)
        var remaining = index.coerceAtLeast(0)
        for (meta in ordered) {
            val count = countOf(meta, excluding)
            if (remaining < count) return ChunkPosition(chunk = meta.n, offset = remaining)
            remaining -= count
        }
        val last = ordered.last()
        return ChunkPosition(chunk = last.n, offset = countOf(last, excluding))
    }

    private fun countOf(meta: ChunkMeta, excluding: Int?): Int =
        if (meta.n == excluding) (meta.count - 1).coerceAtLeast(0) else meta.count

    /**
     * Re-stamp [cards] with `ord`s spread across chunk [n]'s own slice of the ord line.
     *
     * [chunk] assigns `ordForIndex(globalIndex)`, so chunk `n` owns a private range no other chunk can
     * reach into. Renumbering inside it is therefore a purely local write: one chunk record moves, and
     * every other chunk keeps sorting where it did.
     *
     * Preferred over midpointing a single card with [ordBetween] because the chunk record is being
     * rewritten whole anyway — and it removes the "no midpoint left, caller must renumber" case.
     */
    fun renumber(cards: List<Card>, n: Int): List<Card> {
        if (cards.isEmpty()) return cards
        val base = n.toLong() * CHUNK_SIZE * ORD_STRIDE
        val step = (CHUNK_SIZE.toLong() * ORD_STRIDE / (cards.size + 1)).coerceAtLeast(1L)
        return cards.mapIndexed { index, card -> card.copy(ord = base + (index + 1) * step) }
    }
}

/** A card's home: the chunk record it lives in, and its offset within that record. */
internal data class ChunkPosition(val chunk: Int, val offset: Int)
