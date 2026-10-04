package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CardChunking
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.isQuotaExceeded
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.REHOST_MANIFEST_BATCH
import com.github.jvsena42.loopky.data.repository.RehostOutcome
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis

/**
 * What the sweep needs from [DeckRepositoryImpl]. Narrow on purpose: the per-deck write lock and
 * the chunk+manifest pairing stay owned there, so this cannot accidentally write a chunk without
 * the manifest patch that describes it, or take the lock at a second level and deadlock.
 */
internal interface DeckWriteAccess {
    suspend fun localDeck(deckId: String): Deck?

    /** Run [block] holding the deck's write lock. */
    suspend fun <T> inWriteLock(deckId: String, block: suspend () -> T): T

    /**
     * Read-modify-write the manifest. Caller must hold the write lock. [emitChange] is for a patch
     * with something user-visible in it.
     */
    suspend fun patchLocked(deckId: String, emitChange: Boolean = false, patch: (Deck) -> Deck): Deck

    /**
     * Fold chunk [from] into chunk [into], which is left holding [merged], and drop [from] from
     * the manifest. Caller must hold the write lock.
     *
     * On this side of the seam rather than in [DeckCompactor] because the write ordering a merge
     * needs is the one thing about it that can lose cards.
     */
    suspend fun mergeChunksLocked(deck: Deck, into: Int, from: Int, merged: List<Card>): Deck
}

/**
 * Re-hosts the blobs that [DeckRepository.rehostBlob] never sees — the ones nobody has looked at —
 * so a clone becomes fully self-contained rather than only partially (#53).
 *
 * Split from [DeckRepositoryImpl] because it is a self-contained pass over a deck rather than
 * another deck-write operation, and that class was already carrying more than it should.
 */
internal class DeckMediaSweeper(
    private val cardRepo: CardRepository,
    private val mediaRepo: MediaRepository,
    private val decks: DeckWriteAccess,
) {

    suspend fun sweep(deck: Deck, maxChunks: Int): RehostOutcome {
        val state = SweepState(deck.mediaRehostCursor)
        sweepCover(deck, state)

        val toScan = deck.chunks.map { it.n }.filter { it >= deck.mediaRehostCursor }.sorted()
        for ((index, chunk) in toScan.take(maxChunks).withIndex()) {
            // Out of storage: every remaining copy would fail the same way, and re-hosting is
            // itself what fills the quota. Checked before the chunk rather than after, so the
            // cursor is not advanced past one this pass never swept. See #91.
            if (state.quotaError != null) break
            sweepChunk(deck.id, chunk, state)
            state.cursor = chunk + 1
            // The chunk record is already written; the manifest only carries the cursor and the
            // chunk stamps, so patching it per chunk would cost ~200 full manifest PUTs on a
            // 20k-card deck to save re-reading a handful of clean chunks after an interruption.
            if ((index + 1) % REHOST_MANIFEST_BATCH == 0) commit(deck.id, state, done = false)
        }

        val reachedEnd = state.cursor > (deck.chunks.maxOfOrNull { it.n } ?: -1)
        // `missing` deliberately does not block completion: a deleted origin is never coming back,
        // and treating it as unfinished would re-sweep the deck on every run, on every device.
        val complete = reachedEnd && state.failed == 0
        commit(deck.id, state, done = complete)

        Log.d(
            TAG,
            "sweep: ${deck.id} scanned=${state.scanned} rehosted=${state.rehosted} " +
                "missing=${state.missing} failed=${state.failed} complete=$complete",
        )
        // Thrown rather than reported in the outcome, because the caller has to be able to tell
        // this apart from an unfinished pass: a chunk budget wants another run later, a full disk
        // wants the whole job to stop until the user frees space.
        state.quotaError?.let { throw it }
        return state.outcome(complete)
    }

    private suspend fun sweepCover(deck: Deck, state: SweepState) {
        val cover = deck.coverImageRef?.takeIf { it.isRehostable() } ?: return
        val rehosted = copyOnce(deck.id, cover, state) ?: return
        decks.inWriteLock(deck.id) {
            decks.patchLocked(deck.id) {
                it.copy(coverImageRef = it.coverImageRef?.relocatedTo(rehosted, cover.sha256))
            }
        }
    }

    private suspend fun sweepChunk(deckId: String, chunk: Int, state: SweepState) {
        val deck = decks.localDeck(deckId) ?: return
        val stored = cardRepo.readChunk(deck, chunk).getOrElse {
            // One unreadable chunk must not end the sweep. The cursor still advances past it, and
            // a later run re-reads it, because nothing in it was recorded as re-hosted.
            Log.e(TAG, "sweep: chunk $chunk of $deckId unreadable — ${it.message}", it)
            state.failed++
            return
        }
        state.scanned++

        val pinned = stored.flatMap { it.pinnedRefs() }.distinctBy { it.sha256 }
        if (pinned.isEmpty()) return

        var cards = stored
        for (ref in pinned) {
            // Same reason as the chunk loop, one level down: once the disk is full the rest of
            // this chunk's blobs cannot be copied either. What was copied is still written below.
            if (state.quotaError != null) break
            val rehosted = copyOnce(deckId, ref, state)
            if (rehosted != null) cards = cards.map { it.relocatedTo(rehosted, ref.sha256) }
        }
        if (cards == stored) return

        decks.inWriteLock(deckId) {
            cardRepo.writeChunk(deckId, chunk, cards).getOrThrow()
            // Stamped here, patched into the manifest by `commit` along with the cursor.
            state.chunkStamps[chunk] = epochMillis()
        }
    }

    /**
     * Copy one blob, or null when it could not be copied. Reuses a copy already made in this pass,
     * so a blob shared across chunks costs one upload — within a chunk the refs are deduped as
     * they are collected, but across chunks only this record prevents a second copy.
     */
    private suspend fun copyOnce(deckId: String, ref: MediaRef, state: SweepState): MediaRef? {
        state.copied[ref.sha256]?.let { return it }
        val rehosted = mediaRepo.rehost(deckId, ref).getOrElse { error ->
            when {
                error.isNotFound() -> {
                    Log.w(TAG, "sweep: origin gone for ${ref.sha256} — leaving the ref dangling")
                    state.missing++
                }

                error.isQuotaExceeded() -> {
                    Log.e(TAG, "sweep: out of storage copying ${ref.sha256} — stopping", error)
                    // Counted as failed too, so `complete` stays false and the deck stays pending.
                    state.failed++
                    state.quotaError = error
                }

                else -> {
                    Log.e(TAG, "sweep: ${ref.sha256} failed — ${error.message}", error)
                    state.failed++
                }
            }
            return null
        }
        if (rehosted.uri != null) return null
        state.rehosted++
        state.copied[ref.sha256] = rehosted
        return rehosted
    }

    /** Persist progress: the chunk stamps this pass moved, the cursor, and whether it finished. */
    private suspend fun commit(deckId: String, state: SweepState, done: Boolean) {
        if (state.chunkStamps.isEmpty() && !done && state.cursor == state.committedCursor) return
        decks.inWriteLock(deckId) {
            decks.patchLocked(deckId) { current ->
                val chunks = state.chunkStamps.entries.fold(current.chunks) { acc, (n, stamp) ->
                    val count = acc.firstOrNull { it.n == n }?.count ?: 0
                    CardChunking.withChunk(acc, n, count, stamp)
                }
                current.copy(
                    chunks = chunks,
                    mediaRehostCursor = if (done) 0 else state.cursor,
                    mediaRehosted = done,
                )
            }
        }
        state.chunkStamps.clear()
        state.committedCursor = state.cursor
    }

    /** Running totals for one [sweep] pass. */
    private class SweepState(var cursor: Int) {
        var scanned = 0
        var rehosted = 0
        var missing = 0
        var failed = 0
        var committedCursor = cursor

        /** chunk → new `updated_at`, held until the next manifest patch. */
        val chunkStamps = mutableMapOf<Int, Long>()

        /** sha256 → where it was re-hosted to, so one blob is copied once per pass. */
        val copied = mutableMapOf<String, MediaRef>()

        /** The 507 that ended the pass, rethrown once progress is committed. */
        var quotaError: Throwable? = null

        fun outcome(complete: Boolean) = RehostOutcome(scanned, rehosted, missing, failed, complete)
    }

    private companion object {
        const val TAG = "Loopky/DeckSweep"
    }
}

/** Whether this ref points at another author's blob and could be copied under the owning deck. */
internal fun MediaRef.isRehostable(): Boolean =
    uri != null && sha256.isNotEmpty() && (this as? MediaRef.Image)?.isRemote != true

/** Every ref on this card still pointing at another author's blob. */
internal fun Card.pinnedRefs(): List<MediaRef> =
    listOfNotNull(front.imageRef, front.audioRef, back.imageRef, back.audioRef)
        .filter { it.isRehostable() }

/** The ref on this card carrying [sha256] and still pinned, if any. */
internal fun Card.pinnedRef(sha256: String): MediaRef? =
    pinnedRefs().firstOrNull { it.sha256 == sha256 }

internal fun Card.relocatedTo(rehosted: MediaRef, sha256: String): Card = copy(
    front = front.relocatedTo(rehosted, sha256),
    back = back.relocatedTo(rehosted, sha256),
)

internal fun CardSide.relocatedTo(rehosted: MediaRef, sha256: String): CardSide = copy(
    imageRef = imageRef?.relocatedTo(rehosted, sha256),
    audioRef = audioRef?.relocatedTo(rehosted, sha256),
)

/**
 * This ref pointed at the blob's new home, or unchanged when it is not that blob.
 *
 * The path comes from what [MediaRepository.rehost] returned rather than being rebuilt here: it
 * recomputes the digest from the fetched bytes and the extension from the mime type, so a
 * hand-assembled path could disagree with where the copy actually landed.
 */
internal fun <T : MediaRef> T.relocatedTo(rehosted: MediaRef, sha256: String): T {
    if (this.sha256 != sha256 || uri == null) return this
    @Suppress("UNCHECKED_CAST")
    return when (this) {
        is MediaRef.Image -> copy(path = rehosted.path, uri = null) as T
        is MediaRef.Audio -> copy(path = rehosted.path, uri = null) as T
        else -> this
    }
}
