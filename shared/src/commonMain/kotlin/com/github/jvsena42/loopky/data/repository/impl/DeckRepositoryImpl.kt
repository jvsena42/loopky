package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.AccountStamp
import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunking
import com.github.jvsena42.loopky.data.pubky.ManifestDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.SubscriptionDto
import com.github.jvsena42.loopky.data.pubky.absolutizedTo
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.CachedDecks
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.CompactionOutcome
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.PublishProgress
import com.github.jvsena42.loopky.data.repository.RehostOutcome
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.storage.DeckCacheStore
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.ORD_STRIDE
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.platform.BackgroundTasks
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.generateId
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * [DeckRepository] backed by [PubkyClient]. Pubky is the source of truth; an in-memory map is
 * used as a per-session cache so `getLocal` and `listOwned` can return instantly after a sync.
 *
 * See `docs/Architecture.md §8.0` for the on-homeserver layout this implementation writes.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class DeckRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val cardRepo: CardRepository,
    private val revalidator: SessionRevalidator,
    private val tagRepo: TagRepository,
    private val mediaRepo: MediaRepository,
    private val backgroundTasks: BackgroundTasks,
    private val deckCache: DeckCacheStore,
    /**
     * App-scoped: re-hosting outlives whatever screen triggered it, so it cannot run on a
     * `viewModelScope` that dies in `onCleared()`. Injectable so tests can pass `backgroundScope`.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : DeckRepository {

    init {
        // Sequential by construction — `collect` handles one signal at a time — so two cards
        // sharing a blob cannot both copy it.
        scope.launch {
            mediaRepo.pinnedFetches.collect { (deckId, sha256) ->
                rehostBlob(deckId, sha256).onFailure {
                    Log.e(TAG, "rehostBlob: $deckId/$sha256 failed — ${it.message}", it)
                }
            }
        }
    }

    private val cache = mutableMapOf<String, Deck>()
    private val cacheLock = Mutex()

    /**
     * deckId → subscription, or null until first loaded. Null and empty mean different things, so
     * "you follow nothing" is never confused with "we haven't looked yet".
     */
    private var subscriptions: MutableMap<String, SubscriptionDto>? = null
    private val subscriptionLock = Mutex()

    /** Guarded by [subscriptionLock]. Who you follow is per-account; the cache must not outlive it. */
    private val subscriptionAccount = AccountStamp(session)

    /**
     * One write lock per deck, guarding the read-manifest → write-chunk → write-manifest sequence.
     *
     * Not [cacheLock]: that is held only for map access, and holding it across network I/O would
     * serialize `getLocal` app-wide. `kotlinx` [Mutex] is **not reentrant**, so the lock is taken at
     * exactly one level — the public entry points. Anything named `…Locked` assumes it is held.
     */
    private val deckWriteLocks = mutableMapOf<String, Mutex>()
    private val deckWriteLocksGuard = Mutex()

    /**
     * (deckId, sha256) pairs already attempted this session, successful or not. Success has to be
     * recorded or a card left on screen re-copies its blob every render — idempotent PUTs, so
     * silent waste rather than corruption, which is worse because nobody would notice. Failure too:
     * retrying a dangling origin costs the same. A fresh session, or the sweep, tries again.
     */
    private val rehostAttempted = mutableSetOf<Pair<String, String>>()
    private val rehostLock = Mutex()

    /** Guarded by [rehostLock]. */
    private val rehostAccount = AccountStamp(session)

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    override suspend fun getLocal(id: String): Deck? = cacheLock.withLock { cache[id] }

    // runSuspendCatching rather than mapCatching: mapCatching is inline, so its lambda inherits the
    // suspend context and catches the cancellation cacheLock.withLock can throw.
    override suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck> =
        runSuspendCatching {
            val json = pubky.get(PubkyPaths.manifest(authorPubky, deckId)).getOrThrow()
            val deck = loopkyJson.decodeFromString<ManifestDto>(json).toDomain()
            cacheLock.withLock { cache[deck.id] = deck }
            deck
        }

    override suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck> =
        publish(deck, cards, onProgress = {})

    override suspend fun publish(
        deck: Deck,
        cards: List<Card>,
        onProgress: (PublishProgress) -> Unit,
    ): Result<Deck> = runSuspendCatching {
        val author = session.requireSession().identity.pubky

        require(deck.authorPubky == author) {
            "Deck author mismatch: expected $author, got ${deck.authorPubky}"
        }
        withDeckWrite(deck.id) { publishLocked(deck, cards, author, onProgress) }
    }

    /** **The caller must hold [Deck.id]'s write lock.** */
    private suspend fun publishLocked(
        deck: Deck,
        cards: List<Card>,
        author: String,
        onProgress: (PublishProgress) -> Unit,
    ): Deck {
        cards.forEach {
            require(!it.front.isEmpty && !it.back.isEmpty) {
                "Card ${it.id} has an empty side"
            }
        }

        val batches = CardChunking.chunk(cards)
        val chunkMeta = CardChunking.metaFor(batches, deck.updatedAt)
        val manifestUrl = PubkyPaths.manifest(author, deck.id)

        // A deck that shrank leaves chunk records past the new tail, and a tag dropped in the editor
        // leaves a tag record behind. Read what the deck was before the cache entry is replaced.
        val previous = getLocal(deck.id)
        // By chunk number, not `0 until previous.chunks.size`: compaction leaves gaps (#51), so a
        // count would miss exactly the high-numbered records a compacted deck has lying around.
        val staleChunks = previous?.chunks.orEmpty().map { it.n }.filter { it >= batches.size }

        val manifestDeck = deck.copy(cardCount = cards.size, chunks = chunkMeta)

        // Claim the deck *before* uploading its cards. With the manifest written last, a failure
        // partway left orphaned chunks under a deck root with no manifest — invisible to
        // listByAuthor, so the user could neither reach nor delete it.
        val marker = manifestDeck.copy(incomplete = true)
        pubky.putWithSessionRetry(
            manifestUrl,
            loopkyJson.encodeToString(marker.toDto()),
            session,
            revalidator,
        ).getOrThrow()

        onProgress(
            PublishProgress(0, batches.size, 0, cards.size),
        )

        // Written through CardRepository rather than encoded here: it owns the chunk record's shape,
        // and routing through it leaves the card cache warm. Chunk PUTs are idempotent overwrites,
        // so a re-run after a failure just rewrites them. Chunks complete out of order, so the
        // shared counter needs the lock — an unguarded `written++` drops increments and reports a
        // progress bar that never reaches the end.
        val progressLock = Mutex()
        var written = 0
        batches.withIndex().toList().mapConcurrently { (n, batch) ->
            cardRepo.writeChunk(deck.id, n, batch).getOrThrow()
            val soFar = progressLock.withLock { ++written }
            onProgress(
                PublishProgress(
                    chunksWritten = soFar,
                    totalChunks = batches.size,
                    cardsWritten = (soFar * CHUNK_SIZE).coerceAtMost(cards.size),
                    totalCards = cards.size,
                ),
            )
        }

        // Unreachable from the manifest once it shrinks, but left behind they would be served to
        // anyone listing the deck's `cards/` directly. An empty write deletes the record and drops
        // its cards from the cache in one step.
        staleChunks.mapConcurrently { n ->
            cardRepo.writeChunk(deck.id, n, emptyList())
                .onFailure { Log.e(TAG, "publish: stale chunk $n not removed — ${it.message}", it) }
        }

        // Every chunk is up: clear the marker. A reader arriving between the two manifest writes
        // sees `incomplete`, which is honest — the deck is mid-publish.
        val manifestBody = loopkyJson.encodeToString(manifestDeck.toDto())
        pubky.putWithSessionRetry(manifestUrl, manifestBody, session, revalidator).getOrThrow()
        onProgress(
            PublishProgress(batches.size, batches.size, cards.size, cards.size, done = true),
        )

        syncTags(previous, manifestDeck)

        cacheLock.withLock { cache[manifestDeck.id] = manifestDeck }
        _changes.tryEmit(Unit)
        return manifestDeck
    }

    /**
     * Bring the deck's tag records in line with [deck]'s tags so Nexus indexes them, and add the
     * `loopky-deck` marker that puts the deck in the global list — without it the deck is only
     * reachable by people who already follow the author (#40).
     *
     * Called from **every** manifest write that can carry a tag change, not just the first publish
     * (#47): tag records are separate records, so a manifest write alone changes nothing an indexer
     * sees. [previous] is the deck as the manifest carried it before this write; any label it has
     * that [deck] no longer does gets its record removed. Reserved labels are excluded from both
     * ends of the user diff. Language labels need nothing special — they are ordinary
     * [Deck.tags] entries.
     *
     * Best-effort throughout: a failed tag write must not fail the write that triggered it.
     */
    private suspend fun syncTags(previous: Deck?, deck: Deck) {
        tagRepo.putReservedTag(deck.pubkyUri, ReservedTags.DECK).onFailure {
            Log.e(TAG, "syncTags: ${ReservedTags.DECK.value} write failed — ${it.message}", it)
        }

        val current = deck.tags.filterNot { ReservedTags.isReserved(it) }
        for (tag in current) {
            tagRepo.putTag(deck.pubkyUri, tag).onFailure {
                Log.e(TAG, "syncTags: tag '${tag.value}' write failed — ${it.message}", it)
            }
        }

        val dropped = previous?.tags.orEmpty().filterNot { ReservedTags.isReserved(it) } -
            current.toSet()
        for (tag in dropped) {
            tagRepo.removeTag(deck.pubkyUri, tag).onFailure {
                Log.e(TAG, "syncTags: tag '${tag.value}' removal failed — ${it.message}", it)
            }
        }
    }

    /**
     * Write the deck's metadata, keeping whatever chunk table is current rather than the caller's.
     *
     * A ViewModel holds the `Deck` it loaded when the editor opened; anything that rewrote a chunk
     * since is invisible to it, and writing its `chunks`/`cardCount` back would orphan those chunks.
     */
    override suspend fun updateMetadata(deck: Deck): Result<Deck> = runSuspendCatching {
        requireOwnedDeck(deck.id)
        withDeckWrite(deck.id) {
            // Read inside the lock and before the patch: patchDeckLocked replaces the cache entry,
            // so afterwards there is nothing left to diff the tag records against.
            val previous = getLocal(deck.id)
            val updated = patchDeckLocked(deck.id) { current ->
                deck.copy(chunks = current.chunks, cardCount = current.cardCount)
            }
            syncTags(previous, updated)
            updated
        }
    }

    override suspend fun upsertCard(deckId: String, card: Card): Result<Deck> = runSuspendCatching {
        require(!card.front.isEmpty && !card.back.isEmpty) { "Card ${card.id} has an empty side" }
        requireOwnedDeck(deckId)

        withDeckWrite(deckId) {
            val deck = requireNotNull(getLocal(deckId)) { "Deck $deckId is not loaded" }

            // An existing card is rewritten in place; a new one appends to the last chunk with room.
            val existing = locateChunk(deck, card.id)
            val targetChunk = existing ?: CardChunking.appendTarget(deck.chunks)
            val current = cardRepo.readChunk(deck, targetChunk).getOrDefault(emptyList())

            val ord = current.firstOrNull { it.id == card.id }?.ord
                ?: ((current.maxOfOrNull { it.ord } ?: -ORD_STRIDE) + ORD_STRIDE)
            val updated = current.filterNot { it.id == card.id } + card.copy(ord = ord)

            writeChunkAndManifestLocked(deck, targetChunk, updated.inStudyOrder())
        }
    }

    override suspend fun appendCards(deckId: String, cards: List<Card>): Result<Deck> =
        runSuspendCatching {
            cards.forEach {
                require(!it.front.isEmpty && !it.back.isEmpty) { "Card ${it.id} has an empty side" }
            }
            requireOwnedDeck(deckId)

            withDeckWrite(deckId) {
                val deck = requireNotNull(getLocal(deckId)) { "Deck $deckId is not loaded" }
                if (cards.isEmpty()) return@withDeckWrite deck

                // The chunk with room, read once. Everything after it is a fresh trailing chunk, so
                // this is the only existing record the batch has to merge into.
                val firstChunk = CardChunking.appendTarget(deck.chunks)
                val tail = cardRepo.readChunk(deck, firstChunk).getOrDefault(emptyList())
                val existingIds = tail.mapTo(mutableSetOf()) { it.id }
                cards.forEach {
                    require(it.id !in existingIds) { "Card ${it.id} is already in chunk $firstChunk" }
                }

                writeChunksAndManifestLocked(
                    deck,
                    CardChunking.planAppend(
                        cards = cards,
                        firstChunk = firstChunk,
                        tail = tail,
                        ordFloor = highestOrdLocked(deck, tail, firstChunk),
                    ),
                )
            }
        }

    /**
     * The highest `ord` in the deck, for an append to continue past.
     *
     * Under sequential append the last chunk holds the highest ords, so [tail] answers it — except
     * when the append target is a **new** chunk because the last one filled up, and then [tail] is
     * empty. Guessing a floor there puts the appended cards *before* ones already in the deck and
     * silently scrambles study order, so the previous chunk is read instead.
     *
     * **The caller must hold [Deck.id]'s write lock.**
     */
    private suspend fun highestOrdLocked(deck: Deck, tail: List<Card>, firstChunk: Int): Long {
        tail.maxOfOrNull { it.ord }?.let { return it }
        // `chunks[].n`, never `0 until size`: compaction leaves gaps in the numbering (#51).
        val previous = deck.chunks.filter { it.n < firstChunk }.maxByOrNull { it.n } ?: return 0L
        return cardRepo.readChunk(deck, previous.n).getOrDefault(emptyList())
            .maxOfOrNull { it.ord }
            ?: 0L
    }

    override suspend fun deleteCard(deckId: String, cardId: String): Result<Deck> = runSuspendCatching {
        requireOwnedDeck(deckId)

        withDeckWrite(deckId) {
            val deck = requireNotNull(getLocal(deckId)) { "Deck $deckId is not loaded" }
            val chunk = locateChunk(deck, cardId) ?: return@withDeckWrite deck
            val remaining = cardRepo.readChunk(deck, chunk).getOrDefault(emptyList())
                .filterNot { it.id == cardId }

            cardRepo.evict(deckId, cardId)
            writeChunkAndManifestLocked(deck, chunk, remaining).also { updated ->
                // Requested, never performed inline: compacting here would cost a merge per card in
                // a bulk delete, and rewrite records followers have cached, mid-edit. The job is
                // unique work with KEEP, so asking repeatedly is free.
                if (CardChunking.isSparse(updated.chunks)) backgroundTasks.scheduleDeckCompaction()
            }
        }
    }

    override suspend fun moveCard(deckId: String, cardId: String, toIndex: Int): Result<Deck> =
        runSuspendCatching {
            requireOwnedDeck(deckId)

            withDeckWrite(deckId) {
                val deck = requireNotNull(getLocal(deckId)) { "Deck $deckId is not loaded" }
                val from = locateChunk(deck, cardId) ?: return@withDeckWrite deck
                val sourceCards = cardRepo.readChunk(deck, from).getOrThrow().inStudyOrder()
                val card = sourceCards.firstOrNull { it.id == cardId } ?: return@withDeckWrite deck
                val remaining = sourceCards.filterNot { it.id == cardId }

                val target = CardChunking.positionAt(deck.chunks, toIndex, excluding = from)
                if (target.chunk == from) {
                    val reordered = remaining.toMutableList().apply {
                        add(target.offset.coerceIn(0, size), card)
                    }
                    writeChunkAndManifestLocked(deck, from, CardChunking.renumber(reordered, from))
                } else {
                    val landing = cardRepo.readChunk(deck, target.chunk).getOrThrow()
                        .inStudyOrder()
                        .toMutableList()
                        .apply { add(target.offset.coerceIn(0, size), card) }
                    // Landing chunk first. Between the two writes the card is in both, which reads
                    // as one because membership is keyed by id — the other order leaves it in
                    // neither, and a failure there loses it.
                    writeChunksAndManifestLocked(
                        deck,
                        listOf(
                            target.chunk to CardChunking.renumber(landing, target.chunk),
                            from to remaining,
                        ),
                    )
                }
            }
        }

    override suspend fun rehostBlob(deckId: String, sha256: String): Result<Unit> =
        runSuspendCatching {
            val key = deckId to sha256
            // "Attempted" is recorded before the ownership check below, so an attempt made while
            // following a deck would go on suppressing re-hosts of it after signing in as the
            // account that owns it — on the one path that would actually have work to do.
            val firstAttempt = rehostLock.withLock {
                if (rehostAccount.changed()) rehostAttempted.clear()
                rehostAccount.mark()
                rehostAttempted.add(key)
            }
            if (!firstAttempt) return@runSuspendCatching

            // Cache reads only. requireOwnedDeck would fetch the manifest, and a blob not already
            // on screen is the sweep's job, not this path's.
            val deck = getLocal(deckId) ?: return@runSuspendCatching
            if (deck.authorPubky != session.current()?.identity?.pubky) return@runSuspendCatching

            val cover = deck.coverImageRef?.takeIf { it.isRehostable() && it.sha256 == sha256 }
            val cards = cardRepo.listByDeck(deckId).filter { it.pinnedRef(sha256) != null }
            val sample = cover ?: cards.firstNotNullOfOrNull { it.pinnedRef(sha256) }
                ?: return@runSuspendCatching

            // One copy, however many cards reference it — the blob is content-addressed, so every
            // ref carrying that sha stays valid.
            val rehosted = mediaRepo.rehost(deckId, sample).getOrThrow()
            if (rehosted.uri != null) return@runSuspendCatching

            withDeckWrite(deckId) {
                val current = requireNotNull(getLocal(deckId)) { "Deck $deckId is not loaded" }
                writeRehostedCards(current, cards, sha256, rehosted)
                if (cover != null) {
                    patchDeckLocked(deckId, emitChange = false) {
                        it.copy(coverImageRef = it.coverImageRef?.relocatedTo(rehosted, sha256))
                    }
                }
            }
            Log.d(TAG, "rehostBlob: $deckId/$sha256 across ${cards.size} card(s)")
        }

    override suspend fun decksPendingRehost(): List<Deck> = listOwned()
        .filter { it.source?.kind == DeckSource.Kind.Clone && !it.mediaRehosted }

    override suspend fun rehostPendingMedia(
        deckId: String,
        maxChunks: Int,
    ): Result<RehostOutcome> = runSuspendCatching {
        val deck = requireOwnedDeck(deckId)
        if (deck.mediaRehosted) {
            RehostOutcome(0, 0, 0, 0, complete = true)
        } else {
            sweeper.sweep(deck, maxChunks)
        }
    }

    override suspend fun compactDeck(deckId: String, maxMerges: Int): Result<CompactionOutcome> =
        runSuspendCatching {
            val deck = requireOwnedDeck(deckId)
            compactor.compact(deck.id, maxMerges)
        }

    override suspend fun decksPendingCompaction(): List<Deck> =
        listOwned().filter { CardChunking.isSparse(it.chunks) }

    /**
     * Fold chunk [from] into chunk [into], which ends up holding [merged] (#51).
     *
     * **The caller must hold [Deck.id]'s write lock.** Deliberately not routed through
     * [writeChunksAndManifestLocked]: every other chunk write pairs "chunk, then manifest", but a
     * merge *removes* a record, and emptying it first would leave the manifest pointing at a 404.
     * Landing record, then manifest, then source — that order only ever over-counts, and between
     * the first two the moved cards sit in both records, which reads as one card.
     *
     * `updated_at` is not bumped and no change is emitted: compaction moves cards between records
     * without changing one, so telling every follower the author published would be a lie. The
     * per-chunk stamps *are* bumped, which is what makes a follower re-fetch the pair.
     */
    private suspend fun mergeChunksLocked(
        deck: Deck,
        into: Int,
        from: Int,
        merged: List<Card>,
    ): Deck {
        cardRepo.writeChunk(deck.id, into, merged).getOrThrow()

        val now = epochMillis()
        val updated = patchDeckLocked(deck.id, emitChange = false) { current ->
            val chunks = CardChunking.withChunk(
                CardChunking.withChunk(current.chunks, into, merged.size, now),
                from,
                count = 0,
                updatedAt = now,
            )
            current.copy(chunks = chunks, cardCount = CardChunking.cardCount(chunks))
        }

        // Unreachable through the manifest from here on, so a failure costs storage rather than
        // correctness — the next full publish or a deck delete sweeps it.
        cardRepo.writeChunk(deck.id, from, emptyList()).onFailure {
            Log.e(TAG, "compact: chunk $from of ${deck.id} orphaned — ${it.message}", it)
        }
        return updated
    }

    /** [DeckMediaSweeper]'s window onto this class, keeping the write lock owned here. */
    private val writeAccess = object : DeckWriteAccess {
        override suspend fun localDeck(deckId: String): Deck? = getLocal(deckId)

        override suspend fun <T> inWriteLock(deckId: String, block: suspend () -> T): T =
            withDeckWrite(deckId, block)

        override suspend fun patchLocked(deckId: String, patch: (Deck) -> Deck): Deck =
            patchDeckLocked(deckId, emitChange = false, patch = patch)

        override suspend fun mergeChunksLocked(
            deck: Deck,
            into: Int,
            from: Int,
            merged: List<Card>,
        ): Deck = this@DeckRepositoryImpl.mergeChunksLocked(deck, into, from, merged)
    }

    private val sweeper = DeckMediaSweeper(cardRepo, mediaRepo, writeAccess)
    private val compactor = DeckCompactor(cardRepo, writeAccess)

    private suspend fun writeRehostedCards(
        deck: Deck,
        cards: List<Card>,
        sha256: String,
        rehosted: MediaRef,
    ) {
        val byChunk = cards.groupBy { cardRepo.chunkOf(deck.id, it.id) ?: locateChunk(deck, it.id) }
        for ((chunk, inChunk) in byChunk) {
            if (chunk == null) {
                Log.w(TAG, "rehostBlob: ${inChunk.size} card(s) not in any chunk of ${deck.id}")
                continue
            }
            val ids = inChunk.mapTo(mutableSetOf()) { it.id }
            val stored = cardRepo.readChunk(deck, chunk).getOrDefault(emptyList())
            val updated = stored.map { card ->
                if (card.id in ids) card.relocatedTo(rehosted, sha256) else card
            }
            // touchDeck = false: re-hosting rewrites refs to blobs the deck already had, so nothing
            // user-visible changed. Bumping updated_at would tell every follower the author
            // published changes, and emitting `changes` would reload the library.
            writeChunkAndManifestLocked(deck, chunk, updated, touchDeck = false)
        }
    }

    /**
     * The chunk holding [cardId], or null if the deck doesn't contain it. Uses the mapping the card
     * cache recorded; only on a cold cache does it walk chunks, stopping at the first hit — a scan
     * of a 20k-card deck is 200 requests, which would undo the point of chunking.
     */
    private suspend fun locateChunk(deck: Deck, cardId: String): Int? {
        cardRepo.chunkOf(deck.id, cardId)?.let { return it }
        for (meta in deck.chunks) {
            val cards = cardRepo.readChunk(deck, meta.n).getOrDefault(emptyList())
            if (cards.any { it.id == cardId }) return meta.n
        }
        return null
    }

    /** So the manifest read-modify-write inside [block] is atomic. */
    private suspend fun <T> withDeckWrite(deckId: String, block: suspend () -> T): T {
        val lock = deckWriteLocksGuard.withLock { deckWriteLocks.getOrPut(deckId) { Mutex() } }
        return lock.withLock { block() }
    }

    /**
     * Read-modify-write the manifest. **The caller must hold [deckId]'s write lock.**
     *
     * The deck is re-read from the cache rather than taken from a caller's snapshot: every manifest
     * write serializes the *whole* record, so patching a `Deck` fetched before a concurrent write
     * would silently restore its chunk table, orphaning the chunk records — their cards stay on the
     * homeserver but vanish from the deck.
     *
     * [emitChange] is false for writes with nothing user-visible in them (media re-hosting).
     */
    private suspend fun patchDeckLocked(
        deckId: String,
        emitChange: Boolean = true,
        patch: (Deck) -> Deck,
    ): Deck {
        val current = requireNotNull(getLocal(deckId)) { "Deck $deckId is not loaded" }
        val updated = patch(current)
        val body = loopkyJson.encodeToString(updated.toDto())
        pubky.putWithSessionRetry(
            PubkyPaths.manifest(updated.authorPubky, updated.id),
            body,
            session,
            revalidator,
        ).getOrThrow()

        cacheLock.withLock { cache[updated.id] = updated }
        if (emitChange) _changes.tryEmit(Unit)
        return updated
    }

    /**
     * Write one chunk and the manifest entry describing it, as a pair. A single function because
     * the old layout let the two be written independently, which is why a single-card edit could
     * leave the manifest stale forever.
     *
     * The chunk goes first — one the manifest doesn't yet describe is invisible, whereas a manifest
     * pointing at a chunk that was never written is a broken deck.
     *
     * **The caller must hold [Deck.id]'s write lock.** [touchDeck] is false for a write that changes
     * no content, so re-hosting does not light up every follower's "author published" badge.
     */
    private suspend fun writeChunkAndManifestLocked(
        deck: Deck,
        chunk: Int,
        cards: List<Card>,
        touchDeck: Boolean = true,
    ): Deck = writeChunksAndManifestLocked(deck, listOf(chunk to cards), touchDeck)

    /**
     * [writeChunkAndManifestLocked] for a write spanning more than one chunk. The chunks are written
     * in the order given, then described by a **single** manifest patch: two patches would leave a
     * window where the manifest counts the card twice, and cost a second write of the whole table.
     *
     * **The caller must hold [Deck.id]'s write lock.**
     */
    private suspend fun writeChunksAndManifestLocked(
        deck: Deck,
        updates: List<Pair<Int, List<Card>>>,
        touchDeck: Boolean = true,
    ): Deck {
        updates.forEach { (chunk, cards) -> cardRepo.writeChunk(deck.id, chunk, cards).getOrThrow() }

        val now = epochMillis()
        return patchDeckLocked(deck.id, emitChange = touchDeck) { current ->
            val chunks = updates.fold(current.chunks) { acc, (chunk, cards) ->
                CardChunking.withChunk(acc, chunk, cards.size, now)
            }
            current.copy(
                chunks = chunks,
                cardCount = CardChunking.cardCount(chunks),
                updatedAt = if (touchDeck) now else current.updatedAt,
            )
        }
    }

    private suspend fun requireOwnedDeck(deckId: String): Deck {
        val author = session.requireSession().identity.pubky
        val deck = getLocal(deckId) ?: fetchRemote(author, deckId).getOrThrow()
        require(deck.authorPubky == author) {
            "Deck author mismatch: expected $author, got ${deck.authorPubky}"
        }
        return deck
    }

    override suspend fun delete(deckId: String): Result<Unit> = runSuspendCatching {
        // Guarded like every other write since decks you don't own became reachable (#33). Without
        // it, deleting a followed deck built its sweep paths from *your* pubky and ranged over your
        // own namespace looking for a deck that was never there.
        val cached = requireOwnedDeck(deckId)
        withDeckWrite(deckId) { deleteLocked(cached) }
    }

    /** **The caller must hold the deck's write lock.** */
    private suspend fun deleteLocked(cached: Deck) {
        val deckId = cached.id
        val author = cached.authorPubky

        // Sweep everything under the deck root (cards, manifest, media, SRS) so nothing orphans.
        // The listing is the fallback source of paths when the cache is cold.
        val deckRoot = "${PubkyPaths.deckRoot(author, deckId)}/"
        val listedPaths = pubky.listAllEntriesOrEmpty(deckRoot)
        val fallbackPaths = buildList {
            cached.chunks.forEach { add(PubkyPaths.cardChunk(author, deckId, it.n)) }
            add(PubkyPaths.manifest(author, deckId))
        }
        val all = (listedPaths + fallbackPaths).distinct()

        // Manifest last, and on its own: a half-deleted deck without a manifest disappears from
        // listings instead of resurfacing as corrupt, so it must not be racing the rest.
        val (manifests, contents) = all.partition { it.endsWith("/manifest.json") }
        // At the ordinary write width. A sweep used to run at half of it, because the FFI
        // revalidated the session on *every* authenticated call: one delete cost two requests, so a
        // sweep 429'd through the retry budget and left the deck behind. The session is reused now
        // (#105), so narrowing it again only serializes the sweep.
        contents.mapConcurrently { path -> deleteRecordLocked(path) }
        for (path in manifests) {
            deleteRecordLocked(path)
        }

        // Best-effort. The loopky-deck marker goes too, or global browse keeps offering a deck that
        // no longer resolves.
        tagRepo.removeReservedTag(cached.pubkyUri, ReservedTags.DECK).onFailure {
            Log.e(TAG, "delete: ${ReservedTags.DECK.value} removal failed — ${it.message}", it)
        }
        for (tag in cached.tags.filterNot { ReservedTags.isReserved(it) }) {
            tagRepo.removeTag(cached.pubkyUri, tag).onFailure {
                Log.e(TAG, "delete: tag '${tag.value}' removal failed — ${it.message}", it)
            }
        }

        cacheLock.withLock { cache.remove(deckId) }
        _changes.tryEmit(Unit)
    }

    /**
     * Delete one record on the sweep, treating a 404 as done. **The caller must hold the lock.**
     *
     * The paths come from the cached manifest as much as from the listing, so the sweep routinely
     * names a record that is not there — an import that died mid-upload leaves chunk entries whose
     * records were never written. Gone is the outcome being asked for. Throwing instead abandoned
     * the sweep before the manifest, so the deck kept listing and the next attempt hit the same
     * missing record: a half-uploaded deck became undeletable, reported as "this deck no longer
     * exists" on top of a deck that very much did.
     */
    private suspend fun deleteRecordLocked(path: String) {
        pubky.deleteWithSessionRetry(path, session, revalidator).getOrElse { err ->
            if (err.isNotFound()) {
                Log.d(TAG, "delete: $path already gone")
            } else {
                throw err
            }
        }
    }

    override suspend fun listCached(): CachedDecks? {
        val author = session.current()?.identity?.pubky ?: return null
        return runSuspendCatching { deckCache.load(author) }
            .onFailure { Log.e(TAG, "listCached: snapshot unreadable — ${it.message}", it) }
            .getOrNull()
    }

    /**
     * Persist the display snapshot behind [listCached].
     *
     * Written from [listOwned] and [listFollowed] separately, each keeping the other half of the
     * snapshot as it stands, because the two are listed by independent calls that fail
     * independently — a followed deck on an unreachable homeserver must not erase the owned decks
     * that just listed fine.
     */
    private suspend fun cacheSnapshot(owned: List<Deck>? = null, followed: List<Deck>? = null) {
        val author = session.current()?.identity?.pubky ?: return
        runSuspendCatching {
            val existing = deckCache.load(author)
            deckCache.save(
                author,
                CachedDecks(
                    owned = owned ?: existing?.owned.orEmpty(),
                    followed = followed ?: existing?.followed.orEmpty(),
                ),
            )
        }.onFailure { Log.e(TAG, "cacheSnapshot: not written — ${it.message}", it) }
    }

    override suspend fun listOwned(): List<Deck> {
        val author = session.current()?.identity?.pubky ?: return emptyList()
        val owned = listByAuthor(author)
        cacheSnapshot(owned = owned)

        // The self-heal. There is no single app-start hook — session restore is spread across
        // ViewModels — but this runs whenever Home or the library loads, and the job is unique work
        // with KEEP. Recovers a dropped inline signal or a sweep the system cancelled.
        if (owned.any { it.source?.kind == DeckSource.Kind.Clone && !it.mediaRehosted }) {
            backgroundTasks.scheduleMediaRehost()
        }
        // Same self-heal, and free: the density question is answered by the manifests this listing
        // already fetched. Also catches a deck compacted on another device.
        if (owned.any { CardChunking.isSparse(it.chunks) }) {
            backgroundTasks.scheduleDeckCompaction()
        }
        return owned
    }

    /**
     * Throws when the homeserver could not be reached: swallowing that into an empty list would make
     * an offline device indistinguishable from an account with no decks, which reads to the user as
     * "my decks are gone". A genuinely absent path is still an empty list.
     */
    override suspend fun listByAuthor(authorPubky: String): List<Deck> {
        // `shallow` asks for one entry per deck directory rather than every record beneath it. Paged
        // regardless: the homeserver's default page is 100 records, which a single 3,700-card deck
        // overruns on its own. If the flag is ignored, the loop still collects the deep listing.
        val entries = pubky.listAllEntries(PubkyPaths.decksList(authorPubky), shallow = true)
            .getOrElse { if (it.isNotFound()) return emptyList() else throw it }
        val deckIds = parseDeckIdsFrom(entries)
        Log.d(TAG, "listByAuthor: $authorPubky entries=${entries.size} decks=${deckIds.size}")
        // Concurrent: this was one manifest GET per deck, serially, so a library of ten decks paid
        // ten round trips end to end before anything could render.
        val results = deckIds.mapConcurrently { deckId ->
            deckId to fetchRemote(authorPubky, deckId)
        }
        val decks = mutableListOf<Deck>()
        var firstFailure: Throwable? = null
        for ((deckId, result) in results) {
            result
                .onSuccess { decks.add(it) }
                .onFailure { err ->
                    Log.e(TAG, "listByAuthor: manifest fetch failed for $deckId — ${err.message}", err)
                    if (firstFailure == null) firstFailure = err
                }
        }
        // One unreadable deck shouldn't hide the rest, but a listing with decks in it and none
        // readable is a connectivity failure, not an empty library.
        if (decks.isEmpty() && firstFailure != null) throw requireNotNull(firstFailure)
        return decks
    }

    override suspend fun sync(deckId: String): Result<Deck> = runSuspendCatching {
        val remote = fetchRemote(authorOf(deckId), deckId).getOrThrow()

        // fetchByDeck re-reads only the chunks whose `updated_at` moved and rebuilds the cache entry
        // from what it read, so cards dropped remotely fall out without a reconciliation pass — the
        // old index-diff-then-delete loop issued homeserver DELETEs for them, which let a stale
        // local cache delete a card that was still live for its author.
        cardRepo.fetchByDeck(remote).getOrThrow()
        remote
    }

    /**
     * Whose homeserver [deckId] lives on.
     *
     * This used to be the signed-in user unconditionally, so [sync] read `pubky://me/…` for someone
     * else's deck and always failed — silently, because its only caller logs and falls back to the
     * local cache. Following a deck is worthless without this (#33).
     *
     * The cache knows for any deck opened this session; the session fallback keeps a cold-cache sync
     * of your own deck working.
     */
    private suspend fun authorOf(deckId: String): String =
        getLocal(deckId)?.authorPubky
            ?: loadSubscriptions()[deckId]?.author_pubky
            ?: session.requireSession().identity.pubky

    // ── Following someone else's deck (#33) ──────────────────────────────

    override suspend fun followDeck(deck: Deck): Result<Unit> = runSuspendCatching {
        val owner = session.requireSession().identity.pubky
        val record = SubscriptionDto(
            deck_uri = deck.pubkyUri.value,
            author_pubky = deck.authorPubky,
            deck_id = deck.id,
            followed_at = epochMillis(),
            // Following from deck detail means you are looking at it, so it is seen by definition.
            last_seen_updated_at = deck.updatedAt,
        )
        pubky.putWithSessionRetry(
            PubkyPaths.subscription(owner, deck.authorPubky, deck.id),
            loopkyJson.encodeToString(record),
            session,
            revalidator,
        ).getOrThrow()

        loadSubscriptions()
        subscriptionLock.withLock { subscriptions?.put(deck.id, record) }

        // Best-effort: the reserved label is what makes "N people follow this" fall out of the
        // indexer's tagger count, but discoverability must not fail a follow.
        tagRepo.putReservedTag(deck.pubkyUri, ReservedTags.FOLLOWED).onFailure {
            Log.e(TAG, "followDeck: ${ReservedTags.FOLLOWED.value} write failed — ${it.message}", it)
        }
        _changes.tryEmit(Unit)
    }

    override suspend fun unfollowDeck(authorPubky: String, deckId: String): Result<Unit> =
        runSuspendCatching {
            val owner = session.requireSession().identity.pubky
            pubky.deleteWithSessionRetry(
                PubkyPaths.subscription(owner, authorPubky, deckId),
                session,
                revalidator,
            ).getOrThrow()
            subscriptionLock.withLock { subscriptions?.remove(deckId) }

            // The subscription and its label go; the SRS state does not. Re-following must not reset
            // your progress, and review state was never the author's data (Architecture.md §8.3).
            val uri = PubkyUri(PubkyPaths.manifest(authorPubky, deckId))
            tagRepo.removeReservedTag(uri, ReservedTags.FOLLOWED).onFailure {
                Log.e(TAG, "unfollowDeck: ${ReservedTags.FOLLOWED.value} removal failed — ${it.message}", it)
            }
            _changes.tryEmit(Unit)
        }

    override suspend fun isFollowingDeck(deckId: String): Boolean =
        loadSubscriptions().containsKey(deckId)

    override suspend fun listFollowed(): List<Deck> =
        resolveSubscriptions(loadSubscriptions().values.toList())
            .also { cacheSnapshot(followed = it) }

    override suspend fun listFollowedBy(ownerPubky: String): List<Deck> {
        // The signed-in user's own answer comes from the session cache, which also holds a follow
        // made a moment ago — reading their homeserver here would sometimes be a step behind.
        if (ownerPubky == session.current()?.identity?.pubky) return listFollowed()
        return resolveSubscriptions(readSubscriptions(ownerPubky))
    }

    /**
     * Fetch the deck each of [subs] points at, dropping the ones that have gone.
     *
     * A deck its author deleted is gone, not a failure. Anything else may be transient, so one
     * unreachable deck must not hide the rest — while a set where nothing at all read still throws,
     * the same rule [listByAuthor] follows.
     */
    private suspend fun resolveSubscriptions(subs: List<SubscriptionDto>): List<Deck> {
        if (subs.isEmpty()) return emptyList()

        val results = subs.mapConcurrently { sub ->
            sub to fetchRemote(sub.author_pubky, sub.deck_id)
        }
        val decks = mutableListOf<Deck>()
        var firstFailure: Throwable? = null
        for ((sub, result) in results) {
            result
                .onSuccess { decks.add(it) }
                .onFailure { err ->
                    Log.e(TAG, "resolveSubscriptions: ${sub.deck_id} unreadable — ${err.message}", err)
                    if (firstFailure == null && !err.isNotFound()) firstFailure = err
                }
        }
        if (decks.isEmpty() && firstFailure != null) throw requireNotNull(firstFailure)
        return decks
    }

    override suspend fun hasUpdate(deckId: String): Boolean {
        val sub = loadSubscriptions()[deckId] ?: return false
        val deck = getLocal(deckId) ?: return false
        return deck.updatedAt > sub.last_seen_updated_at
    }

    override suspend fun markSeen(deck: Deck) {
        val sub = loadSubscriptions()[deck.id] ?: return
        if (sub.last_seen_updated_at >= deck.updatedAt) return

        val owner = session.current()?.identity?.pubky ?: return
        val updated = sub.copy(last_seen_updated_at = deck.updatedAt)
        pubky.putWithSessionRetry(
            PubkyPaths.subscription(owner, sub.author_pubky, sub.deck_id),
            loopkyJson.encodeToString(updated),
            session,
            revalidator,
        ).onFailure {
            // Cosmetic: the worst case is the "updated" dot showing one extra time.
            Log.e(TAG, "markSeen: ${deck.id} not recorded — ${it.message}", it)
            return
        }
        subscriptionLock.withLock { subscriptions?.put(deck.id, updated) }
        _changes.tryEmit(Unit)
    }

    override suspend fun clone(source: Deck, title: String): Result<Deck> = runSuspendCatching {
        val newTitle = title.trim()
        // Refused rather than defaulted: the copy joins a library that already holds the deck it
        // forked, and a silent fall back to the source's title is what the rename exists to stop.
        require(newTitle.isNotEmpty()) { "clone: the copy needs a title of its own" }
        val me = session.requireSession().identity.pubky
        // A fetch, not a cache read: for a deck you don't own, nothing has ever loaded its cards.
        val sourceCards = cardRepo.fetchByDeck(source).getOrThrow()
        val now = epochMillis()
        val newId = generateId()

        val cards = sourceCards.inStudyOrder().map { card ->
            card.copy(
                // A fresh id per card, so grading the clone never moves the original's review state
                // and vice versa — they are keyed by (author, deck, card).
                id = generateId(),
                deckId = newId,
                updatedAt = now,
                front = card.front.absolutizedTo(source),
                back = card.back.absolutizedTo(source),
            )
        }

        val deck = source.copy(
            id = newId,
            title = newTitle,
            authorPubky = me,
            createdAt = now,
            updatedAt = now,
            coverImageRef = source.coverImageRef?.absolutizedTo(source.authorPubky, source.id),
            cardCount = cards.size,
            // publish() writes the real chunk table; the source's is about the source's records.
            chunks = emptyList(),
            incomplete = false,
            source = DeckSource(
                kind = DeckSource.Kind.Clone,
                uri = source.pubkyUri.value,
                importedAt = now,
            ),
        )

        val published = publish(deck, cards).getOrThrow()

        // On the *source* manifest, so credit for the copy accrues to the original author rather
        // than to the fork. Best-effort, like every other reserved-tag write.
        tagRepo.putReservedTag(source.pubkyUri, ReservedTags.CLONED).onFailure {
            Log.e(TAG, "clone: ${ReservedTags.CLONED.value} write failed — ${it.message}", it)
        }

        // You own a copy now, so tracking the author's edits is noise. Best-effort: a failed
        // unfollow leaves you with both, which is untidy rather than broken.
        if (isFollowingDeck(source.id)) {
            unfollowDeck(source.authorPubky, source.id).onFailure {
                Log.e(TAG, "clone: unfollowing ${source.id} failed — ${it.message}", it)
            }
        }

        // A fresh clone's media is entirely pinned to the source author. Scheduled from the repo so
        // it is not a screen's responsibility to remember, and so it is testable in commonTest.
        backgroundTasks.scheduleMediaRehost()

        Log.d(TAG, "clone: ${source.id} -> $newId (${cards.size} cards)")
        published
    }

    /** Refs pointing at [source]'s blobs rather than at paths under a deck they were never uploaded to. */
    private fun CardSide.absolutizedTo(source: Deck): CardSide = copy(
        imageRef = imageRef?.absolutizedTo(source.authorPubky, source.id),
        audioRef = audioRef?.absolutizedTo(source.authorPubky, source.id),
    )

    private suspend fun loadSubscriptions(): Map<String, SubscriptionDto> {
        // The account check comes *before* the cache is served, not after. The other way round —
        // which is what this was — hands a freshly created pubky the previous user's subscriptions,
        // and Home shows it decks it never followed.
        subscriptionLock.withLock {
            if (subscriptionAccount.changed()) subscriptions = null
            subscriptions
        }?.let { return it.toMap() }
        val owner = session.current()?.identity?.pubky ?: return emptyMap()

        val loaded = readSubscriptions(owner).associateByTo(mutableMapOf()) { it.deck_id }
        subscriptionLock.withLock {
            subscriptions = loaded
            subscriptionAccount.mark()
        }
        return loaded.toMap()
    }

    /**
     * [owner]'s subscription records straight off their homeserver, uncached on purpose: the session
     * cache is the *signed-in* account's, and this also answers for strangers ([listFollowedBy]). A
     * record that will not read or parse is skipped — one corrupt entry is not a reason to report
     * that somebody follows nothing.
     */
    private suspend fun readSubscriptions(owner: String): List<SubscriptionDto> {
        val loaded = mutableListOf<SubscriptionDto>()
        // Deep, and it must stay deep: subscriptions nest as `subscriptions/{author}/{deckId}.json`,
        // so a shallow listing would return author directories rather than records.
        for (path in pubky.listAllEntriesOrEmpty(PubkyPaths.subscriptionsRoot(owner))) {
            val json = pubky.get(path).getOrElse {
                Log.e(TAG, "readSubscriptions: $path unreadable — ${it.message}", it)
                continue
            }
            runCatching { loopkyJson.decodeFromString<SubscriptionDto>(json) }
                .onSuccess { loaded.add(it) }
                .onFailure { Log.e(TAG, "readSubscriptions: $path is not a subscription", it) }
        }
        return loaded
    }

    /**
     * Deck ids out of a `decks/` listing, from either shape the homeserver can return: `…/decks/{id}/`
     * when it honours `shallow`, or `…/decks/{id}/manifest.json` when it does not.
     *
     * Decoded per entry rather than scanned across the raw payload: a substring scan of a JSON array
     * cannot tell where one URL ends, so a marker hit with no following `/` runs into the next entry
     * and yields debris. A deep listing never produces that; a shallow one can.
     */
    private fun parseDeckIdsFrom(entries: List<String>): List<String> {
        val marker = "/${PubkyPaths.APP_NAMESPACE}/decks/"
        return entries.mapNotNullTo(linkedSetOf()) { entry ->
            entry.substringAfter(marker, missingDelimiterValue = "")
                .substringBefore('/')
                .takeIf { it.isNotEmpty() }
        }.toList()
    }

    private companion object {
        const val TAG = "Loopky/DeckRepo"

        /** Room for a burst of mutations while a collector is mid-reload; oldest is dropped. */
        const val CHANGE_BUFFER = 8
    }
}
