package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CardChunking
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis

/**
 * Puts a whole deck into a given order by re-chunking it (#449).
 *
 * A reorder moves cards between chunk records, and each record is its own request, so the hazard
 * is the one a merge has, multiplied: rewrite chunk 0 without a card before chunk 1 holds it, lose
 * the session in between, and the card is on no record at all. No ordering of the writes avoids
 * that when two chunks each hold a card the other needs, so every changed chunk is written twice,
 * with the manifest in between:
 *
 * 1. manifest: [Deck.reorderPending] set, nothing else;
 * 2. each changed chunk as its new cards **plus** whatever it still held — after this pass every
 *    card is in its new record, and nothing has left its old one;
 * 3. manifest: the new chunk table, old stamps;
 * 4. each of those chunks as its new cards only;
 * 5. manifest: fresh stamps, the marker cleared;
 * 6. delete the records the new table no longer lists.
 *
 * A card in two records reads as one, because membership is keyed by id — and both copies carry
 * the card's **new** `ord`, so a reader arriving between the passes sorts the deck correctly
 * rather than by whichever copy it read last.
 *
 * **The stamps move only in step 5**, so a reader with a warm cache keeps the whole deck as it
 * had it until the records are final, and re-reads each once. **A run that dies leaves the marker
 * set**, and the next one — which derives its plan from the records as they are — re-stamps
 * *every* chunk. That is the part a reader depends on: the dead run may have finished chunks this
 * one no longer needs to write, and without a new stamp nobody holding the old contents of those
 * is ever told to read them again.
 *
 * **While the marker is set, [DeckRepositoryImpl] refuses card writes** with
 * [com.github.jvsena42.loopky.data.repository.DeckReorderPendingException]: a card in two records
 * is deleted from one and stays in the deck, or edited in one and read from the other.
 */
internal class DeckReorderer(
    private val cardRepo: CardRepository,
    private val decks: DeckWriteAccess,
) {

    /**
     * **The caller must hold [deck]'s write lock.** [cardIds] has to name every card in the deck
     * exactly once; anything else throws before the first write.
     */
    suspend fun reorderLocked(deck: Deck, cardIds: List<String>): Deck {
        val records = deck.chunks.sortedBy { it.n }
            .mapConcurrently { meta -> meta.n to readRecord(deck, meta.n) }
            .toMap()
        val byId = records.values.flatten().associateBy { it.id }
        require(cardIds.size == byId.size && cardIds.toSet() == byId.keys) {
            "A reorder has to name each of the deck's ${byId.size} cards exactly once"
        }

        val target = CardChunking.planReorder(deck.chunks, cardIds.map(byId::getValue))
        // In order *and* inside its slice. A record left alone keeps its `ord`s while its
        // neighbours are renumbered into theirs, so one whose `ord`s had strayed would sort among
        // the next record's cards — the deck in the wrong order, reported as done.
        val changed = target.filter { (n, cards) ->
            val stored = records[n].orEmpty().inStudyOrder()
            stored.map { it.id } != cards.map { it.id } || !CardChunking.inSlice(stored, n)
        }
        val resuming = deck.reorderPending
        // The run that died may already have shortened the table, and a record it meant to delete
        // is then on no list this one can see; ask the homeserver what is actually there.
        val stray = if (resuming) decks.storedChunkNumbers(deck) else emptySet()
        val dropped = (records.keys + stray) - target.keys
        if (changed.isEmpty() && dropped.isEmpty() && !resuming) return deck

        val placed = target.values.flatten().associateBy { it.id }
        val leftovers = changed.mapValues { (n, cards) ->
            val staying = cards.mapTo(mutableSetOf()) { it.id }
            records[n].orEmpty().filterNot { it.id in staying }.map { placed.getValue(it.id) }
        }

        if (!resuming) decks.patchLocked(deck.id) { it.copy(reorderPending = true) }

        changed.entries.toList().mapConcurrently { (n, cards) ->
            cardRepo.writeChunk(deck.id, n, cards + leftovers.getValue(n)).getOrThrow()
        }
        decks.patchLocked(deck.id) { current ->
            current.withTable(target, stampOf = { n -> current.chunks.firstOrNull { it.n == n }?.updatedAt })
        }

        changed.filterKeys { leftovers.getValue(it).isNotEmpty() }.entries.toList()
            .mapConcurrently { (n, cards) -> cardRepo.writeChunk(deck.id, n, cards).getOrThrow() }

        val now = epochMillis()
        val restamped = if (resuming) target.keys else changed.keys
        val updated = decks.patchLocked(deck.id, emitChange = true) { current ->
            current
                .withTable(
                    target,
                    stampOf = { n -> current.chunks.firstOrNull { it.n == n }?.updatedAt.takeIf { n !in restamped } },
                    now = now,
                )
                .copy(updatedAt = now, reorderPending = false)
        }

        // Unreachable through the manifest from here on, so a failure costs storage, not cards.
        dropped.forEach { n ->
            cardRepo.writeChunk(deck.id, n, emptyList()).onFailure {
                Log.e(TAG, "reorder: chunk $n of ${deck.id} orphaned — ${it.message}", it)
            }
        }
        Log.d(TAG, "reorder: ${deck.id} rewrote ${changed.size} of ${target.size} chunks, dropped ${dropped.size}")
        return updated
    }

    /** The deck describing [target], each chunk keeping the stamp [stampOf] gives it, else [now]. */
    private fun Deck.withTable(
        target: Map<Int, List<Card>>,
        stampOf: (Int) -> Long?,
        now: Long = epochMillis(),
    ): Deck {
        val table = target.map { (n, cards) -> ChunkMeta(n = n, count = cards.size, updatedAt = stampOf(n) ?: now) }
            .sortedBy { it.n }
        return copy(chunks = table, cardCount = CardChunking.cardCount(table))
    }

    /**
     * One record's cards. A 404 is an empty record; any other failure stops the reorder, because
     * planning around a chunk whose contents are unknown is how its cards would get dropped.
     */
    private suspend fun readRecord(deck: Deck, n: Int): List<Card> =
        cardRepo.readChunk(deck, n).getOrElse { if (it.isNotFound()) emptyList() else throw it }

    private companion object {
        const val TAG = "Loopky/DeckReorder"
    }
}
