package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.repository.CachedDecks
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.CompactionOutcome
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.PinnedBlob
import com.github.jvsena42.loopky.data.repository.PublishProgress
import com.github.jvsena42.loopky.data.repository.RehostOutcome
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.MediaRef
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The two repositories a card command touches, and nothing else.
 *
 * Hand-written here rather than reached for from `:shared`'s `commonTest`, which is not on this
 * module's classpath. Everything a given command does not call throws instead of returning a
 * plausible zero: a fake that answers politely turns "this command quietly stopped calling the
 * homeserver" into a passing test.
 */
class FakeDeckRepository(
    private var deck: Deck,
    private val onUpsert: (Card) -> Deck = { deck.copy(cardCount = deck.cardCount + 1) },
    /**
     * What `publish` does. A `.apkg` import is the only command that publishes, and the failing
     * case is as load-bearing as the succeeding one: an aborted publish is what decides whether
     * the blobs it already uploaded get swept back out.
     */
    private val onPublish: (Deck, List<Card>) -> Result<Deck> = { published, cards ->
        Result.success(published.copy(cardCount = cards.size))
    },
    /** Decks `import --resume` matches its `--title` against. */
    private val owned: List<Deck> = emptyList(),
    /**
     * What `appendCards` does. It defaults to landing them, because it is the ordinary path for
     * `card add --from-file` as well as for `import --resume`; a test that wants the append to be
     * the failure says so.
     */
    private val onAppend: (List<Card>) -> Result<Deck> = { cards ->
        Result.success(deck.copy(cardCount = deck.cardCount + cards.size))
    },
    /**
     * What `upsertCard` should fail with for a given attempt, or null to let it through.
     *
     * Takes the attempt number as well as the card, because a batch's whole point is what it does
     * *between* attempts — a 500 that clears on the retry and one that does not are different
     * outcomes, and only the count tells them apart.
     */
    private val upsertFails: (Card, Int) -> Throwable? = { _, _ -> null },
    /**
     * What a read of the deck fails with — `sync` and `fetchRemote` alike — or null for a deck
     * that is there.
     *
     * `deck create --if-not-exists` is the caller that needs the failing case: "there is no such
     * deck" and "the homeserver did not answer" have to reach it as different things, or a
     * network wobble publishes the duplicate the flag exists to prevent.
     */
    private val readFails: Throwable? = null,
) : DeckRepository {

    /** Every `upsertCard` call, failed ones included — `upserted` holds only the ones that landed. */
    val upsertAttempts = mutableListOf<Card>()

    val upserted = mutableListOf<Card>()

    /**
     * Every deck handed to `updateMetadata`, so a test can assert on what `deck edit` *wrote*
     * rather than only on what it printed — including that it wrote nothing at all.
     */
    val metadataWrites = mutableListOf<Deck>()

    /** The cards handed to `publish`, so a test can read back what a card ended up referencing. */
    val published = mutableListOf<Card>()

    /** The cards `import --resume` appended to a deck it matched. */
    val appended = mutableListOf<Card>()

    /** How many cards each `appendCards` call carried — the shape [appended] flattens away. */
    val appendBatches = mutableListOf<Int>()

    override suspend fun sync(deckId: String): Result<Deck> {
        syncCalls += deckId
        return readFails?.let { Result.failure(it) } ?: Result.success(deck)
    }

    /** Every deck id `sync` was asked for, so a test can assert a read did *not* happen. */
    val syncCalls = mutableListOf<String>()

    /**
     * Every `(authorPubky, deckId)` a manifest read was aimed at.
     *
     * The pubky is the assertable half: `deck create --id` asks for the *caller's own* namespace,
     * and that — rather than a field check afterwards — is what stops a followed deck's id being
     * reported as taken.
     */
    val fetchRemoteCalls = mutableListOf<Pair<String, String>>()

    override suspend fun publish(
        deck: Deck,
        cards: List<Card>,
        onProgress: (PublishProgress) -> Unit,
    ): Result<Deck> {
        published += cards
        return onPublish(deck, cards)
    }

    override suspend fun listOwned(): List<Deck> = owned

    /**
     * Nothing. The cached snapshot is a first-paint device cache for the app's screens; the CLI
     * never reads it, and answering with [owned] here would hide a command that started to.
     */
    override suspend fun listCached(): CachedDecks? = null

    override suspend fun upsertCard(deckId: String, card: Card): Result<Deck> {
        upsertAttempts += card
        val attempt = upsertAttempts.count { it.id == card.id }
        upsertFails(card, attempt)?.let { return Result.failure(it) }
        upserted += card
        deck = onUpsert(card)
        return Result.success(deck)
    }

    /**
     * Mirrors the real one where it matters to a caller: the chunk table and `card_count` come
     * from the deck on the homeserver, never from the deck the caller assembled.
     */
    override suspend fun updateMetadata(deck: Deck): Result<Deck> {
        metadataWrites += deck
        this.deck = deck.copy(chunks = this.deck.chunks, cardCount = this.deck.cardCount)
        return Result.success(this.deck)
    }

    override val changes: SharedFlow<Unit> = MutableSharedFlow()

    private fun no(name: String): Nothing = error("FakeDeckRepository.$name is not part of this test")

    override suspend fun getLocal(id: String): Deck? = deck.takeIf { it.id == id }
    override suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck> {
        fetchRemoteCalls += authorPubky to deckId
        return readFails?.let { Result.failure(it) } ?: Result.success(deck)
    }
    override suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck> = no("publish")
    override suspend fun delete(deckId: String): Result<Unit> = no("delete")
    override suspend fun appendCards(deckId: String, cards: List<Card>): Result<Deck> {
        appended += cards
        appendBatches += cards.size
        return onAppend(cards)
    }
    override suspend fun deleteCard(deckId: String, cardId: String): Result<Deck> = no("deleteCard")
    override suspend fun moveCard(deckId: String, cardId: String, toIndex: Int): Result<Deck> = no("moveCard")
    override suspend fun rehostBlob(deckId: String, sha256: String): Result<Unit> = no("rehostBlob")
    override suspend fun rehostPendingMedia(deckId: String, maxChunks: Int): Result<RehostOutcome> =
        no("rehostPendingMedia")
    override suspend fun decksPendingRehost(): List<Deck> = no("decksPendingRehost")
    override suspend fun compactDeck(deckId: String, maxMerges: Int): Result<CompactionOutcome> =
        no("compactDeck")
    override suspend fun decksPendingCompaction(): List<Deck> = no("decksPendingCompaction")
    override suspend fun listByAuthor(authorPubky: String): List<Deck> = no("listByAuthor")
    override suspend fun followDeck(deck: Deck): Result<Unit> = no("followDeck")
    override suspend fun unfollowDeck(authorPubky: String, deckId: String): Result<Unit> = no("unfollowDeck")
    override suspend fun isFollowingDeck(deckId: String): Boolean = no("isFollowingDeck")
    override suspend fun listFollowed(): List<Deck> = no("listFollowed")
    override suspend fun listFollowedBy(ownerPubky: String): List<Deck> = no("listFollowedBy")
    override suspend fun hasUpdate(deckId: String): Boolean = no("hasUpdate")
    override suspend fun markSeen(deck: Deck) = no("markSeen")
    override suspend fun clone(source: Deck, title: String): Result<Deck> = no("clone")
}

class FakeCardRepository(
    private val existing: List<Card> = emptyList(),
    /**
     * The deck laid out as records, for the paged `card list`. Null means no chunk may be read —
     * which is the assertion that matters for the unpaged path: it must not walk the table.
     */
    private val chunks: Map<Int, List<Card>>? = null,
) : CardRepository {

    /** Which chunks a listing actually fetched, in order. The whole point of `--limit` is this list. */
    val chunksRead = mutableListOf<Int>()

    override suspend fun listByDeck(deckId: String): List<Card> = existing
    override suspend fun fetchByDeck(deck: Deck): Result<List<Card>> = Result.success(existing)
    override suspend fun get(deckId: String, cardId: String): Card? = existing.firstOrNull { it.id == cardId }

    private fun no(name: String): Nothing = error("FakeCardRepository.$name is not part of this test")

    override suspend fun writeChunk(deckId: String, chunk: Int, cards: List<Card>): Result<Unit> = no("writeChunk")
    override suspend fun readChunk(deck: Deck, chunk: Int): Result<List<Card>> {
        val table = chunks ?: no("readChunk")
        chunksRead += chunk
        return Result.success(table[chunk].orEmpty())
    }
    override suspend fun chunkOf(deckId: String, cardId: String): Int? = no("chunkOf")
    override suspend fun evict(deckId: String, cardId: String) = no("evict")
}

/** A deck with nothing interesting in it but the fields a card command reads. */
fun testDeck(id: String = "d1", cardCount: Int = 0, chunks: List<ChunkMeta> = emptyList()) = Deck(
    id = id,
    authorPubky = "pk:test",
    title = "Test deck",
    description = null,
    coverImageRef = null,
    tags = emptyList(),
    createdAt = 0L,
    updatedAt = 0L,
    cardCount = cardCount,
    chunks = chunks,
    source = null as DeckSource?,
)

/**
 * Blob storage that records rather than writes.
 *
 * The one place a fake has to be faithful about *identity*: `putImage` hands back a content
 * addressed ref, and both the per-run upload memo and `--resume`'s dedupe are built on that being
 * the same sha for the same bytes.
 */
class FakeMediaRepository(private val failAfter: Int = Int.MAX_VALUE) : MediaRepository {

    val puts = mutableListOf<ByteArray>()
    val deleted = mutableListOf<String>()

    override val pinnedFetches: SharedFlow<PinnedBlob> = MutableSharedFlow()

    override suspend fun putImage(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Image> {
        if (puts.size >= failAfter) return Result.failure(IllegalStateException("no space left"))
        puts += bytes
        val sha = bytes.fold(7) { acc, byte -> acc * 31 + byte }.toString()
        return Result.success(
            MediaRef.Image(path = "media/$sha.jpg", mime = mime, sha256 = sha, width = null, height = null),
        )
    }

    override suspend fun delete(deckId: String, ref: MediaRef): Result<Unit> {
        deleted += ref.sha256
        return Result.success(Unit)
    }

    private fun no(name: String): Nothing = error("FakeMediaRepository.$name is not part of this test")

    override suspend fun putAudio(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Audio> =
        no("putAudio")
    override suspend fun get(authorPubky: String, deckId: String, ref: MediaRef): Result<ByteArray> = no("get")
    override suspend fun rehost(deckId: String, ref: MediaRef): Result<MediaRef> = no("rehost")
}
