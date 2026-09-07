package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.AccountStamp
import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.SrsChunkDto
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.data.storage.PendingReview
import com.github.jvsena42.loopky.data.storage.PendingReviewStore
import com.github.jvsena42.loopky.data.storage.StudyProgressStore
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckCounts
import com.github.jvsena42.loopky.domain.model.DeckMastery
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.domain.model.isDue
import com.github.jvsena42.loopky.domain.model.isFullyMastered
import com.github.jvsena42.loopky.domain.model.isNew
import com.github.jvsena42.loopky.domain.model.masteryShare
import com.github.jvsena42.loopky.domain.model.maturityThresholdDays
import com.github.jvsena42.loopky.domain.model.previewIntervals
import com.github.jvsena42.loopky.domain.model.review
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.localDayIndex
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * [SrsRepository] backed by [PubkyClient]. Review state lives batched at
 * `/pub/loopky/srs/{authorPubky}/{deckId}/{n}.json` — on **your** homeserver, keyed by the deck's
 * author, for any deck including ones you do not own.
 *
 * SRS has the opposite access pattern to cards: writes are one card at a time and frequent, reads
 * need everything at once. So reads are chunked and **writes buffer in memory and flush per chunk**
 * — grading 100 cards costs one chunk write at the end instead of 100 as it goes.
 *
 * The flush cannot live in the study ViewModel: `viewModelScope` is cancelled in `onCleared()`, so
 * a flush started there would be killed before it finished. This class owns an app-scoped scope
 * instead, and also flushes every [FLUSH_EVERY] reviews so a crash costs a few cards, not a session.
 */
// LongParameterList: every one is a collaborator this repo genuinely needs, and the last is the
// injectable scope that makes the async flush testable.
@Suppress("TooManyFunctions", "LongParameterList")
class SrsRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val deckRepository: DeckRepository,
    private val cardRepository: CardRepository,
    private val pendingReviews: PendingReviewStore,
    private val settingsRepository: SettingsRepository,
    private val studyProgress: StudyProgressStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    /** Injected so a test can cross midnight without touching the device clock. */
    private val dayIndex: (Long) -> Int = ::localDayIndex,
) : SrsRepository {

    /** Deck-and-author scoped, so card ids cannot collide across decks. */
    private data class StateKey(val authorPubky: String, val deckId: String, val cardId: String)

    private val cache = mutableMapOf<StateKey, SrsState>()

    /**
     * Which chunk each state belongs to. Recorded when the state is loaded or written, never
     * re-derived — a flush computing the chunk differently from the write that dirtied it would
     * persist the state into the wrong record.
     */
    private val stateChunks = mutableMapOf<StateKey, Int>()

    /** Which (author, deck) chunks hold unflushed reviews. */
    private val dirty = mutableSetOf<Triple<String, String, Int>>()

    /**
     * The individual states behind [dirty], which is chunk-granular. The journal has to record the
     * reviews themselves: a chunk also contains states already on the homeserver, and re-writing
     * those on restore would resurrect stale values over newer ones.
     */
    private val dirtyStates = mutableSetOf<StateKey>()

    /** deckId → the author whose deck it is, learned when the deck is read. */
    private val deckAuthors = mutableMapOf<String, String>()
    private val cacheLock = Mutex()
    private var sinceFlush = 0

    /** Guarded by [cacheLock]. Review state is per-reader, so none of this may outlive the account. */
    private val cacheAccount = AccountStamp(session)

    /**
     * Take [cacheLock], dropping everything in it first if the account has changed. Every read and
     * write goes through this rather than `cacheLock` directly, because a check at each of the
     * twenty-odd call sites is one missed path away from serving a new account the previous one's
     * review history.
     */
    private suspend fun <T> withCaches(block: suspend () -> T): T = cacheLock.withLock {
        evictOnAccountChangeLocked()
        block()
    }

    /**
     * Drop every cached map when the signed-in account changes. Caller holds [cacheLock].
     *
     * Safe to lose the dirty set: reviews are journalled as they are graded, not only when they
     * flush, and the journal is owner-scoped, so the previous account's unflushed work waits on the
     * device for it to sign back in. [journalRestored] resets for the same reason.
     */
    private fun evictOnAccountChangeLocked() {
        if (cacheAccount.changed()) {
            cache.clear()
            stateChunks.clear()
            dirty.clear()
            dirtyStates.clear()
            deckAuthors.clear()
            loadedDecks.clear()
            journalRestored = false
            sinceFlush = 0
        }
        // Re-stamped on every acquisition, not only after an eviction: the stamp is what makes the
        // *next* change detectable, so skipping it would mean the caches were never claimed by
        // anyone and no later switch would register.
        cacheAccount.mark()
    }

    private val _changes = MutableSharedFlow<String>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: SharedFlow<String> = _changes.asSharedFlow()

    private val _flushFailures = MutableSharedFlow<ErrorReason>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // replay = 1 so a screen subscribing after the failure still learns about it. flushAsync runs on
    // this class's own scope precisely because the study screen may be going away as it starts.
    override val flushFailures: SharedFlow<ErrorReason> = _flushFailures.asSharedFlow()

    /**
     * Followed decks count too (#33). Review state is already author-keyed and lands on your own
     * homeserver whoever wrote the deck, so a followed deck needs no storage change to be studied —
     * only to be reachable from here, which owned-decks-only made it not.
     */
    override suspend fun dueToday(): List<Card> {
        settingsRepository.ensureLoaded()
        if (restoreJournal()) flushAsync()
        // Reviews from every deck before new cards from any of them, not deck-by-deck: a session
        // that opens on material you already know earns the right to introduce new material, and
        // interleaving by deck would put deck one's unseen cards ahead of deck two's overdue ones.
        val queues = studiableDecks().map { queueForDeck(it.id) }
        return queues.flatMap { it.due } + queues.flatMap { it.new }
    }

    override suspend fun countsForDeck(deckId: String): DeckCounts =
        queueForDeck(deckId).let { DeckCounts(due = it.due.size, new = it.new.size) }

    override suspend fun mastery(deckId: String, cardIds: List<String>): DeckMastery? {
        if (cardIds.isEmpty()) return null
        val states = statesForDeck(deckId)
        // A cold cache would report a mature deck as 0%, which is the exact lie this replaced.
        if (states.isEmpty() && cardIds.isNotEmpty() && !isLoadedFor(deckId)) return null
        val threshold = currentSettings().maturityThresholdDays
        return DeckMastery(
            share = masteryShare(cardIds, states, threshold),
            isComplete = isFullyMastered(cardIds, states, threshold),
        )
    }

    private suspend fun isLoadedFor(deckId: String): Boolean {
        val author = withCaches { deckAuthors[deckId] } ?: return false
        return withCaches { loadedDecks.contains(author to deckId) }
    }

    override suspend fun countsToday(decks: List<Deck>?): Map<String, DeckCounts> {
        if (restoreJournal()) flushAsync()
        return (decks ?: studiableDecks()).associate { deck ->
            deck.id to queueForDeck(deck.id, known = deck)
                .let { DeckCounts(due = it.due.size, new = it.new.size) }
        }
    }

    /** The ones you own plus the ones you follow. */
    private suspend fun studiableDecks(): List<Deck> {
        val owned = deckRepository.listOwned()
        // A followed deck lives on someone else's homeserver, so listing it can fail on its own.
        // That must cost you the followed decks, not your whole queue.
        val followed = runSuspendCatching { deckRepository.listFollowed() }.getOrElse {
            Log.e(TAG, "dueToday: followed decks unavailable — ${it.message}", it)
            emptyList()
        }
        return (owned + followed).distinctBy { it.id }
    }

    override suspend fun dueForDeck(deckId: String): List<Card> =
        queueForDeck(deckId).let { it.due + it.new }

    /** Kept split so callers can count the two halves separately. */
    private data class DeckQueue(val due: List<Card>, val new: List<Card>)

    /**
     * Cards actually due for review, soonest first, then cards never seen, in the deck's own study
     * order. The order is the point: a 1669-card import used to present every card as due and hand
     * the user an unclimbable wall (#101 §7). Nothing is capped — the new-cards goal is a goal, and
     * withholding cards is exactly what it must not do.
     */
    private suspend fun queueForDeck(deckId: String, known: Deck? = null): DeckQueue {
        settingsRepository.ensureLoaded()
        // Before anything reads the cache: a journal from a previous process holds reviews newer
        // than the homeserver's, and a queue built without them would re-show graded cards.
        // Recovered reviews are sent straight away — nothing else would, short of the user starting
        // another session and grading FLUSH_EVERY more cards.
        if (restoreJournal()) flushAsync()
        // A caller that just listed the library holds the manifest already, so only the *chunk*
        // half of a sync is owed — [DeckRepository.sync] would re-GET a manifest fetched seconds
        // ago, once per deck, behind the spinner the listing is already being waited on for.
        val deck = if (known != null) {
            cardRepository.fetchByDeck(known)
                .onFailure { Log.e(TAG, "queueForDeck: chunks unreadable for $deckId — ${it.message}", it) }
            known
        } else {
            deckRepository.sync(deckId)
                .onFailure { Log.e(TAG, "queueForDeck: sync failed for $deckId — ${it.message}", it) }
                .getOrNull() ?: deckRepository.getLocal(deckId)
        }
        val author = deck?.authorPubky ?: session.current()?.identity?.pubky
            ?: return DeckQueue(emptyList(), emptyList())
        withCaches { deckAuthors[deckId] = author }

        val cards = cardRepository.listByDeck(deckId)
        loadChunksFor(author, deckId)

        val now = epochMillis()
        val paired = cards.map { card -> card to stateOf(author, deckId, card.id) }
        return DeckQueue(
            due = paired
                .filter { (_, state) -> state.isDue(now) }
                .sortedWith(compareBy({ (_, state) -> state?.dueAt ?: 0L }, { (card, _) -> card.id }))
                .map { it.first },
            // listByDeck already returns study order, so unseen cards keep the author's sequence.
            new = paired.filter { (_, state) -> state.isNew() }.map { it.first },
        )
    }

    override suspend fun nextDueAt(): Long? {
        val now = epochMillis()
        return withCaches { cache.values.map { it.dueAt } }
            .filter { it > now }
            .minOrNull()
    }

    override suspend fun stateFor(deckId: String, cardId: String): SrsState? {
        val author = authorFor(deckId)
        return withCaches { cache[StateKey(author, deckId, cardId)] }
    }

    override suspend fun statesForDeck(deckId: String): Map<String, SrsState> {
        val author = authorFor(deckId)
        return withCaches {
            cache.entries
                .filter { it.key.authorPubky == author && it.key.deckId == deckId }
                .associate { it.key.cardId to it.value }
        }
    }

    /**
     * Cache-only, so this must not call [dueForDeck] — that syncs the manifest, the whole cost this
     * exists to avoid. [deckAuthors] records which decks have been loaded, so iterating it is what
     * bounds the answer to decks the cache can speak for.
     */
    override suspend fun dueCountsCached(): Map<String, DeckCounts> {
        val now = epochMillis()
        val decks = withCaches { deckAuthors.toMap() }
        return decks.mapValues { (deckId, _) ->
            val states = statesForDeck(deckId)
            val cards = cardRepository.listByDeck(deckId)
            DeckCounts(
                due = cards.count { states[it.id].isDue(now) },
                new = cards.count { states[it.id].isNew() },
            )
        }
    }

    override suspend fun review(card: Card, grade: SrsGrade): Result<SrsState> = runSuspendCatching {
        require(isStudiable(card.deckId)) {
            "Deck ${card.deckId} is neither owned nor followed — follow or clone it to study it"
        }
        val wasNew = stateOf(authorFor(card.deckId), card.deckId, card.id).isNew()
        // Idempotent and single-flight, so after the first load this is a field read. Called here
        // rather than relying on the queue having been built first: that ordering happens to hold
        // today, and a grade scheduled from stale defaults is not a failure anyone would notice.
        settingsRepository.ensureLoaded()
        val author = authorFor(card.deckId)
        val current = stateOf(author, card.deckId, card.id)
        val next = current.review(card.id, grade, epochMillis(), currentSettings())
        upsert(card.deckId, next).getOrThrow()
        recordStudied(isNewCard = wasNew)
        next
    }

    /**
     * The reverse half of a pair: re-schedule from where the pair started, not from the forward
     * result already written. No [recordStudied] — one card studied both ways is one review.
     */
    override suspend fun reviewFrom(
        card: Card,
        base: SrsState?,
        grade: SrsGrade,
    ): Result<SrsState> = runSuspendCatching {
        require(isStudiable(card.deckId)) {
            "Deck ${card.deckId} is neither owned nor followed — follow or clone it to study it"
        }
        settingsRepository.ensureLoaded()
        val next = base.review(card.id, grade, epochMillis(), currentSettings())
        upsert(card.deckId, next).getOrThrow()
        next
    }

    private val _dailyProgress = MutableStateFlow(DailyStudyProgress())
    override val dailyProgress: StateFlow<DailyStudyProgress> = _dailyProgress.asStateFlow()

    private val progressLock = Mutex()
    private var progressRestored = false

    override suspend fun refreshDailyProgress() {
        progressLock.withLock { restoreProgressLocked() }
    }

    override suspend fun markGoalCelebrated() {
        val updated = progressLock.withLock {
            restoreProgressLocked()
            if (_dailyProgress.value.goalCelebrated) return
            val next = _dailyProgress.value.copy(goalCelebrated = true)
            _dailyProgress.value = next
            next
        }
        // Persisted immediately rather than with the next review: the celebration is shown once a
        // day, and a process killed between showing it and the next grade would show it again.
        saveProgress(updated) { "markGoalCelebrated: could not persist — $it" }
    }

    /**
     * [isNewCard] is measured *before* grading, since grading is what stops it being new. It can
     * over-count if a chunk read failed silently — an inflated goal is the cheap failure, and a
     * network read per grade is not worth it for a motivational number.
     */
    private suspend fun recordStudied(isNewCard: Boolean) {
        val today = dayIndex(epochMillis())
        val updated = progressLock.withLock {
            restoreProgressLocked()
            val current = _dailyProgress.value.forToday(today)
            val next = current.copy(
                newCards = current.newCards + if (isNewCard) 1 else 0,
                reviews = current.reviews + 1,
            )
            _dailyProgress.value = next
            next
        }
        saveProgress(updated) { "recordStudied: could not persist today's progress — $it" }
    }

    /** Guarded by [progressLock]. Today's tally belongs to whoever earned it. */
    private val progressAccount = AccountStamp(session)

    /**
     * Persist today's counters, if there is anyone to persist them for. Signed out there is no owner
     * to attribute them to, and writing them under the next account to sign in is the bug this guards.
     */
    private suspend fun saveProgress(progress: DailyStudyProgress, message: (String?) -> String) {
        val owner = session.current()?.identity?.pubky ?: return
        runSuspendCatching { studyProgress.save(owner, progress) }
            .onFailure { Log.w(TAG, message(it.message)) }
    }

    /** Caller holds [progressLock]. Reads the stored counters once, then keeps them in memory. */
    private suspend fun restoreProgressLocked() {
        val today = dayIndex(epochMillis())
        // Today's tally is a claim about a person, so a change of account starts it again from zero
        // rather than congratulating the new one for the last one's session.
        if (progressAccount.changed()) progressRestored = false
        progressAccount.mark()
        if (!progressRestored) {
            progressRestored = true
            val owner = session.current()?.identity?.pubky
            val stored = owner?.let {
                runSuspendCatching { studyProgress.load(it) }
                    .onFailure { Log.w(TAG, "restoreProgress: FAILED — ${it.message}") }
                    .getOrNull()
            }
            _dailyProgress.value = (stored ?: DailyStudyProgress(dayIndex = today)).forToday(today)
            return
        }
        // Re-applied on every read, not only at restore: a session left open across midnight would
        // otherwise carry yesterday's count all through the new day.
        _dailyProgress.value = _dailyProgress.value.forToday(today)
    }

    override suspend fun previewIntervals(card: Card): Map<SrsGrade, String> {
        settingsRepository.ensureLoaded()
        val author = authorFor(card.deckId)
        return stateOf(author, card.deckId, card.id)
            .previewIntervals(card.id, epochMillis(), currentSettings())
    }

    override suspend fun previewIntervalsFrom(
        card: Card,
        base: SrsState?,
        cap: SrsGrade?,
    ): Map<SrsGrade, String> {
        settingsRepository.ensureLoaded()
        return base.previewIntervals(card.id, epochMillis(), currentSettings(), cap)
    }

    /** No suspension, no network: [SettingsRepository.studySettings] is warmed, then simply read. */
    private fun currentSettings(): StudySettings = settingsRepository.studySettings.value.settings

    /**
     * Whether review state may be written for [deckId] at all: it has to be a deck you own or follow.
     *
     * Browsing someone else's deck from Discover is not keeping it, and grading it would strand
     * review state under a deck that never appears in your library or your due queue. Keeping the
     * deck is the deliberate act that earns SRS state, and this is that rule at the repository.
     */
    private suspend fun isStudiable(deckId: String): Boolean {
        val deck = deckRepository.getLocal(deckId) ?: return false
        if (deck.authorPubky == session.current()?.identity?.pubky) return true
        return runSuspendCatching { deckRepository.isFollowingDeck(deckId) }.getOrDefault(false)
    }

    override suspend fun upsert(deckId: String, state: SrsState): Result<Unit> = runSuspendCatching {
        val author = authorFor(deckId)
        val key = StateKey(author, deckId, state.cardId)
        val chunk = withCaches { stateChunks[key] } ?: chunkFor(deckId, state.cardId)

        val shouldFlush = withCaches {
            cache[key] = state
            stateChunks[key] = chunk
            dirty.add(Triple(author, deckId, chunk))
            dirtyStates.add(key)
            ++sinceFlush >= FLUSH_EVERY
        }
        _changes.tryEmit(deckId)
        // Journalled before the flush is attempted, not after it fails: the window this closes is
        // the process dying, and a write that only happens on the failure path does not cover it.
        writeJournal()

        // Bounded loss: a crash costs at most FLUSH_EVERY reviews, not the whole session.
        if (shouldFlush) flush().getOrThrow()
    }

    override suspend fun flush(): Result<Unit> = runSuspendCatching {
        restoreJournal()
        val (pending, pendingStates) = withCaches {
            val chunks = dirty.toList()
            val states = dirtyStates.toList()
            dirty.clear()
            dirtyStates.clear()
            sinceFlush = 0
            chunks to states
        }
        if (pending.isEmpty()) return@runSuspendCatching

        val owner = session.requireSession().identity.pubky
        // Not error handling — a restore-on-abnormal-exit, which is why it rethrows unconditionally
        // and catches Throwable. Cancellation has to restore too: flush() is reachable from
        // review()/upsert() on viewModelScope, so closing the study screen mid-flush would otherwise
        // drop up to FLUSH_EVERY reviews on the floor.
        @Suppress("TooGenericExceptionCaught")
        try {
            pending.mapConcurrently { (author, deckId, chunk) ->
                writeChunk(owner, author, deckId, chunk)
            }
        } catch (err: Throwable) {
            // Put them back so the next flush retries rather than silently losing progress.
            withCaches {
                dirty.addAll(pending)
                dirtyStates.addAll(pendingStates)
            }
            throw err
        }
        // Only now is the journal redundant. Anything graded while the writes were in flight is
        // still dirty and is re-journalled by this same call.
        writeJournal()
        Unit
    }

    override fun flushAsync() {
        scope.launch {
            flush().onFailure { err ->
                Log.e(TAG, "flushAsync: FAILED — ${err.message}", err)
                // Surfaced rather than only logged. The reviews are safe — back in the dirty set and
                // on disk — but silence is what let a full quota eat a whole study session without
                // the user seeing anything go wrong (#91).
                _flushFailures.tryEmit(err.toErrorReason())
            }
        }
    }

    /**
     * Fold a journal left by a previous process back into the buffer, once per instance.
     *
     * Restored into [cache] unconditionally, unlike [loadChunksFor]'s read: a journalled review is
     * newer than the homeserver's by definition — it is the write that never landed.
     *
     * Returns true when it recovered something, so the caller can send it. Safe to call from
     * [flush]: the guard flag is set before the load, so the nested call returns false.
     */
    private suspend fun restoreJournal(): Boolean {
        val alreadyRestored = withCaches {
            val was = journalRestored
            journalRestored = true
            was
        }
        if (alreadyRestored) return false

        // Nothing to fold in for nobody: signed out, there is no account to attribute a review to.
        val owner = session.current()?.identity?.pubky ?: return false
        val entries = runSuspendCatching { pendingReviews.load() }.getOrElse {
            Log.e(TAG, "restoreJournal: unreadable — ${it.message}", it)
            return false
        }
            // The journal is one device-wide file and may hold another account's unflushed work.
            // Restoring theirs would show this user reviews they never did and, on the next flush,
            // write them to *this* account's homeserver.
            .filter { it.ownerPubky == owner }
        if (entries.isEmpty()) return false

        withCaches {
            for (entry in entries) {
                val key = StateKey(entry.authorPubky, entry.deckId, entry.cardId)
                cache[key] = entry.toDomain()
                stateChunks[key] = entry.chunk
                dirty.add(Triple(entry.authorPubky, entry.deckId, entry.chunk))
                dirtyStates.add(key)
                deckAuthors[entry.deckId] = entry.authorPubky
            }
        }
        Log.d(TAG, "restoreJournal: recovered ${entries.size} unflushed review(s)")
        return true
    }

    /**
     * Mirror the unflushed buffer to disk. Written whole rather than appended to, so it can never
     * describe more than is pending — a stale entry would re-write an old state over a newer one.
     */
    private suspend fun writeJournal() {
        val owner = session.current()?.identity?.pubky ?: return
        val mine = withCaches {
            dirtyStates.mapNotNull { key ->
                val state = cache[key] ?: return@mapNotNull null
                val chunk = stateChunks[key] ?: return@mapNotNull null
                state.toPendingReview(owner, key.authorPubky, key.deckId, chunk)
            }
        }
        // "Written whole" means whole *for this account*. The file is device-wide, so overwriting it
        // with only our own entries would throw away another account's unflushed reviews.
        val theirs = runSuspendCatching { pendingReviews.load() }
            .getOrDefault(emptyList())
            .filter { it.ownerPubky != owner }
        runSuspendCatching { pendingReviews.save(theirs + mine) }
            .onFailure { Log.e(TAG, "writeJournal: FAILED — ${it.message}", it) }
    }

    /** Guarded by [cacheLock]. */
    private var journalRestored = false

    private suspend fun writeChunk(owner: String, author: String, deckId: String, chunk: Int) {
        val states = withCaches {
            cache.filterKeys { key ->
                key.authorPubky == author && key.deckId == deckId && stateChunks[key] == chunk
            }.values.map { it.toDto() }
        }
        val body = loopkyJson.encodeToString(
            SrsChunkDto(deck_id = deckId, author_pubky = author, chunk = chunk, states = states),
        )
        pubky.putWithSessionRetry(
            PubkyPaths.srsChunk(owner, author, deckId, chunk),
            body,
            session,
            revalidator,
        ).getOrThrow()
    }

    /**
     * Read every SRS chunk for a deck, concurrently, once per session.
     *
     * The chunk set is **discovered** by listing rather than derived from the card count: deriving
     * it would miss any chunk the writer placed elsewhere, and [chunkFor] does exactly that when a
     * card's deck position is not yet known, so a guessed range would make those reviews
     * permanently unreadable.
     */
    private suspend fun loadChunksFor(author: String, deckId: String) {
        val owner = session.current()?.identity?.pubky ?: return
        val loaded = withCaches { loadedDecks.contains(author to deckId) }
        if (loaded) return

        val root = PubkyPaths.srsRoot(owner, author, deckId)
        // Paged: one chunk record per ~100 cards against a default page of 100 records, so a
        // 10,000-card deck would have its later chunks silently omitted — which the discovery
        // contract above forbids, since an unread chunk both hides review state and lets a later
        // write clobber it.
        val urls = pubky.listAllEntriesOrEmpty(root)

        urls.mapConcurrently { url ->
            pubky.get(url)
                .mapCatching { loopkyJson.decodeFromString<SrsChunkDto>(it) }
                .onSuccess { dto ->
                    withCaches {
                        dto.states.forEach { state ->
                            val key = StateKey(author, deckId, state.card_id)
                            // A buffered review is newer than what is on the homeserver.
                            if (key !in cache) cache[key] = state.toDomain()
                            stateChunks[key] = dto.chunk
                        }
                    }
                }
                .onFailure { Log.e(TAG, "loadChunksFor: $url unreadable — ${it.message}", it) }
        }
        withCaches { loadedDecks.add(author to deckId) }
    }

    private val loadedDecks = mutableSetOf<Pair<String, String>>()

    private suspend fun stateOf(author: String, deckId: String, cardId: String): SrsState? =
        withCaches { cache[StateKey(author, deckId, cardId)] }

    private suspend fun authorFor(deckId: String): String =
        withCaches { deckAuthors[deckId] }
            ?: deckRepository.getLocal(deckId)?.authorPubky
            ?: session.requireSession().identity.pubky

    /**
     * Which SRS chunk a card's state belongs in, for a card seen for the first time.
     *
     * Mirrors the card's own position in the deck, so a deck's SRS chunks line up with its card
     * chunks and a session touches few of them. Falls back to a hash when the card order is not
     * loaded — still correct, just less tidy. Either way it is recorded in [stateChunks] and never
     * recomputed.
     */
    private suspend fun chunkFor(deckId: String, cardId: String): Int {
        val index = cardRepository.listByDeck(deckId).indexOfFirst { it.id == cardId }
        return if (index >= 0) {
            index / CHUNK_SIZE
        } else {
            (cardId.hashCode().toUInt() % CHUNK_BUCKETS.toUInt()).toInt()
        }
    }

    companion object {
        private const val TAG = "Loopky/SrsRepo"

        /** Room for a burst of reviews while a collector is mid-reload; oldest is dropped. */
        private const val CHANGE_BUFFER = 8

        /** Reviews buffered before an automatic flush. Caps what a crash can cost. */
        internal const val FLUSH_EVERY = 20

        /** Fallback bucket count when a card's deck position is unknown. */
        private const val CHUNK_BUCKETS = 64
    }
}

/**
 * Journal entry for one unflushed review. Mirrors [SrsState] plus the routing the flush needs —
 * whose deck it is and which chunk it belongs in — since neither is recoverable from the state alone.
 */
private fun SrsState.toPendingReview(
    ownerPubky: String,
    authorPubky: String,
    deckId: String,
    chunk: Int,
) = PendingReview(
    ownerPubky = ownerPubky,
    authorPubky = authorPubky,
    deckId = deckId,
    chunk = chunk,
    cardId = cardId,
    dueAt = dueAt,
    intervalDays = intervalDays,
    easeFactor = easeFactor,
    repetitions = repetitions,
    lastGrade = lastGrade?.ordinal,
)

private fun PendingReview.toDomain() = SrsState(
    cardId = cardId,
    dueAt = dueAt,
    intervalDays = intervalDays,
    easeFactor = easeFactor,
    repetitions = repetitions,
    // An out-of-range ordinal means a journal written by a build with a different grade set. The
    // grade is a display detail; dropping it keeps the schedule, which is the part that matters.
    lastGrade = lastGrade?.let { SrsGrade.entries.getOrNull(it) },
)
