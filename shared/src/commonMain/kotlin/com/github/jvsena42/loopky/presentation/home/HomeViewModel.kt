package com.github.jvsena42.loopky.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.requiresReauth
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.domain.model.DEFAULT_NEW_CARDS_PER_DAY
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckCounts
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val identityRepository: IdentityRepository,
    private val deckRepository: DeckRepository,
    private val srsRepository: SrsRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<HomeEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<HomeEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    init {
        load()
        // Publishing and deleting happen on other destinations while this tab stays composed,
        // so without this a new deck doesn't show up until the process restarts.
        viewModelScope.launch {
            deckRepository.changes.collect { load(silent = true) }
        }
        // Same reason for reviews: studying runs on its own destination, so without this the
        // "due today" count and the per-deck badges keep the values they had before the session.
        // A review can only move those counts, so it gets the cache-only recount rather than a
        // full load — see [refreshDueCounts].
        viewModelScope.launch {
            srsRepository.changes.collect { refreshDueCounts() }
        }
        // Today's tally and the goal it is measured against are both live: the counter moves as
        // cards are graded on another destination, and the goal moves when Settings writes it.
        viewModelScope.launch {
            srsRepository.dailyProgress.collect { progress ->
                _state.update { current ->
                    (current as? HomeUiState.Content)?.copy(
                        doneToday = progress.reviews,
                        newCardsToday = progress.newCards,
                    ) ?: current
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.studySettings.collect { snapshot ->
                _state.update { current ->
                    (current as? HomeUiState.Content)
                        ?.copy(newCardsGoal = snapshot.settings.newCardsPerDayGoal)
                        ?: current
                }
            }
        }
    }

    fun onRefresh() = load()

    /** [silent] keeps existing content on screen while a background refresh runs. */
    private fun load(silent: Boolean = false) {
        // Cancel rather than bail out: a change that lands mid-load must not be dropped.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: fetching session + decks (silent=$silent)")
            if (!silent) paintFromCache()
            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()
            // Carry who the user is, not what to call them — the platform layer owns the words.
            val identity = session?.identity

            runSuspendCatching { deckRepository.listOwned() }
                .onSuccess { owned ->
                    // Decks you follow are studiable and belong in the queue (#33). They live on
                    // other homeservers, so losing them must not fail a working Home.
                    val followed = runSuspendCatching { deckRepository.listFollowed() }
                        .onFailure { Log.e(TAG, "load: followed decks unavailable — ${it.message}", it) }
                        .getOrDefault(emptyList())
                    val decks = (owned + followed).distinctBy { it.id }

                    val next = if (decks.isEmpty()) HomeUiState.Empty(identity) else content(identity, decks)
                    _state.update { next }
                    Log.d(TAG, "load: owned=${owned.size} followed=${followed.size}")
                    refreshIdentity(identity)
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    if (err.requiresReauth()) {
                        Log.d(TAG, "load: session expired — signing out")
                        runSuspendCatching { identityRepository.signOut() }
                        _state.update { HomeUiState.Error(
                            identity = identity,
                            reason = ErrorReason.SessionExpired,
                        ) }
                        _effects.emit(HomeEffect.NavigateToOnboarding)
                    } else {
                        _state.update { HomeUiState.Error(
                            identity = identity,
                            reason = err.toErrorReason(),
                        ) }
                    }
                }
        }
    }

    /** Today's numbers, for a user who has decks. Split out of [load] purely for its length. */
    private suspend fun content(identity: PubkyIdentity?, decks: List<Deck>): HomeUiState.Content {
        runSuspendCatching { srsRepository.refreshDailyProgress() }
        val progress = srsRepository.dailyProgress.value
        // The decks travel with the call: without them this re-lists owned and followed decks and
        // re-fetches every manifest [load] has just read, doubling Home's round trips.
        val counts = runSuspendCatching { srsRepository.countsToday(decks) }.getOrDefault(emptyMap())
        val dueCount = counts.values.sumOf { it.due }
        val newCount = counts.values.sumOf { it.new }
        return HomeUiState.Content(
            identity = identity,
            dueToday = dueCount,
            newToday = newCount,
            doneToday = progress.reviews,
            newCardsToday = progress.newCards,
            newCardsGoal = settingsRepository.studySettings.value.settings.newCardsPerDayGoal,
            decks = decks.map { it.toSummary(counts[it.id] ?: DeckCounts()) },
            // Only meaningful when there is nothing at all left to study; skip the lookup otherwise.
            nextDueAtMillis = if (dueCount + newCount == 0) {
                runSuspendCatching { srsRepository.nextDueAt() }.getOrNull()
            } else {
                null
            },
        )
    }

    /**
     * Show the library this device last saw, so a cold start opens on Home rather than a spinner.
     *
     * The counts are not guessed — review state is not cached across processes — so the state goes
     * out with [HomeUiState.Content.countsKnown] false and the screen shows no number until the
     * real ones land. Everything here is replaced by the load already running behind it.
     */
    private suspend fun paintFromCache() {
        val cached = runSuspendCatching { deckRepository.listCached() }.getOrNull()
        val decks = cached?.let { (it.owned + it.followed).distinctBy { deck -> deck.id } }
        if (decks.isNullOrEmpty()) {
            _state.update { HomeUiState.Loading }
            return
        }
        val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
        // Today's tally is the one number here that is *not* a guess: it lives in the device-local
        // StudyProgressStore, so restoring it costs a disk read and no round trip. Left out, the
        // goal line under the dash read "0 of 20 new cards today" to someone who had already done
        // fifteen — the same false claim [countsKnown] exists to remove, one line lower.
        runSuspendCatching { srsRepository.refreshDailyProgress() }
        val progress = srsRepository.dailyProgress.value
        // The mirror, not `ensureLoaded`: the goal is a synced record, and `ensureLoaded` reads the
        // homeserver after restoring the mirror — a round trip back on the path this cache exists
        // to shorten. This half is a disk read, so the goal shown is the user's own rather than the
        // built-in default. The real record still arrives with the load running behind this.
        runSuspendCatching { settingsRepository.restoreCachedSettings() }
        _state.update {
            HomeUiState.Content(
                identity = session?.identity,
                dueToday = 0,
                doneToday = progress.reviews,
                newCardsToday = progress.newCards,
                newCardsGoal = settingsRepository.studySettings.value.settings.newCardsPerDayGoal,
                decks = decks.map { deck -> deck.toSummary(DeckCounts()) },
                countsKnown = false,
            )
        }
    }

    /**
     * Recompute the due badges from what the SRS repository already holds in memory.
     *
     * This fires once per graded card, because studying runs on its own destination while Home
     * stays composed behind it. It used to be a full [load], which re-listed every owned and
     * followed deck, re-synced each of their manifests through `dueToday()`, and re-fetched the
     * user's profile — per card (#102). Nothing but the counts can change under a review.
     *
     * Decks the cache cannot speak for keep the count they already had, rather than dropping to
     * zero: [SrsRepository.dueCountsCached] reports only what it knows.
     */
    private suspend fun refreshDueCounts() {
        val counts = runSuspendCatching { srsRepository.dueCountsCached() }
            .onFailure { Log.e(TAG, "refreshDueCounts: FAILED — ${it.message}", it) }
            .getOrNull() ?: return
        if (counts.isEmpty()) return

        val decks = (_state.value as? HomeUiState.Content)?.decks ?: return
        val updated = decks.map { deck ->
            counts[deck.id]?.let { deck.copy(dueCount = it.due, newCount = it.new) } ?: deck
        }
        val due = updated.sumOf { it.dueCount }
        val fresh = updated.sumOf { it.newCount }
        // Only meaningful once there is nothing left to study, and it is the one part of this that
        // can suspend on something other than the cache — so it is skipped otherwise, as in [load].
        val nextDueAt = if (due + fresh == 0) {
            runSuspendCatching { srsRepository.nextDueAt() }.getOrNull()
        } else {
            null
        }
        _state.update { current ->
            (current as? HomeUiState.Content)
                ?.copy(dueToday = due, newToday = fresh, decks = updated, nextDueAtMillis = nextDueAt)
                ?: current
        }
    }

    /**
     * The session's copy of the name is written at sign-in, so a name edited on Profile would
     * otherwise never reach the greeting. Runs after first paint — the greeting never waits on it.
     */
    private suspend fun refreshIdentity(stored: PubkyIdentity?) {
        if (stored == null) return
        val fresh = identityRepository.fetchProfile(stored.pubky).getOrNull() ?: return
        if (fresh.displayName == stored.displayName) return
        _state.update { current ->
            when (current) {
                is HomeUiState.Content -> current.copy(identity = fresh)
                is HomeUiState.Empty -> HomeUiState.Empty(fresh)
                is HomeUiState.Error -> current.copy(identity = fresh)
                HomeUiState.Loading -> current
            }
        }
    }

    fun onStartStudyClick() {
        viewModelScope.launch { _effects.emit(HomeEffect.NavigateStartStudy) }
    }

    fun onCreateDeckClick() {
        viewModelScope.launch { _effects.emit(HomeEffect.NavigateCreateDeck) }
    }

    fun onBrowseExamplesClick() {
        viewModelScope.launch { _effects.emit(HomeEffect.NavigateBrowseExamples) }
    }

    fun onSeeAllDecksClick() {
        viewModelScope.launch { _effects.emit(HomeEffect.NavigateAllDecks) }
    }

    fun onDeckClick(deckId: String) {
        // The author travels with the deck id: a followed deck's manifest is on someone else's
        // homeserver, so deck detail cannot fetch it from an id alone on a cold cache.
        val author = (_state.value as? HomeUiState.Content)
            ?.decks?.firstOrNull { it.id == deckId }?.authorPubky
        viewModelScope.launch { _effects.emit(HomeEffect.NavigateDeck(deckId, author)) }
    }

    private fun Deck.toSummary(counts: DeckCounts): DeckSummary = DeckSummary(
        id = id,
        title = title,
        authorPubky = authorPubky,
        cardCount = cardCount,
        dueCount = counts.due,
        newCount = counts.new,
        coverEmoji = coverEmoji ?: title.firstOrNull()?.uppercaseChar()?.toString() ?: "📚",
        coverImage = coverImageRef,
    )

    companion object {
        private const val TAG = "Loopky/HomeVM"
    }
}

sealed interface HomeUiState {
    data object Loading : HomeUiState

    /** [identity] is null only while signed out — the screen greets a nameless user then. */
    data class Empty(val identity: PubkyIdentity?) : HomeUiState
    data class Content(
        val identity: PubkyIdentity?,
        /** Cards previously graded whose review has come up. Never-seen cards are [newToday]. */
        val dueToday: Int,
        /** Cards never graded, across every studiable deck. Not "late" — just not met yet. */
        val newToday: Int = 0,
        val doneToday: Int = 0,
        val newCardsToday: Int = 0,
        val newCardsGoal: Int = DEFAULT_NEW_CARDS_PER_DAY,
        val decks: List<DeckSummary>,
        /**
         * Whether [dueToday], [newToday] and the per-deck badges are real numbers rather than
         * placeholders.
         *
         * False only on the cached first paint, where the decks are known and the review state is
         * not — SRS state is not cached across processes. Everything that reads a count has to ask
         * this first: with it ignored, a launch opened on "🎉 You're all caught up" over a deck
         * with two cards due, and then corrected itself a second later.
         */
        val countsKnown: Boolean = true,
        /**
         * When the next card becomes reviewable, if there is nothing at all to study. Lets the UI
         * say "you're caught up, next review in 4h" instead of reusing the no-decks empty state,
         * which told users who owned decks to "create or import a deck".
         */
        val nextDueAtMillis: Long? = null,
    ) : HomeUiState {
        /**
         * Nothing left to study at all — reviews *and* new cards.
         *
         * New cards have to count: a freshly imported deck has zero due, and treating that as
         * "all caught up" would greet a 1669-card import with a congratulation (#101 §7).
         */
        val isCaughtUp: Boolean get() = countsKnown && dueToday == 0 && newToday == 0

        /**
         * The headline number: everything overdue, plus as many new cards as today's goal still
         * has room for.
         *
         * This is what stops a 1669-card import shouting "1669". The queue behind it is still
         * uncapped — studying past the goal works — but the number offered up front is the day's
         * intent rather than the whole backlog.
         *
         * Once the goal is met the number stops being a target and becomes simply what is left.
         * Clamping it to zero while cards remain would put "0 cards to review" above a Start
         * studying button and a deck row reading "2 new" — telling the user to stop, which is the
         * one thing a goal that never withholds cards must not do.
         */
        val studyTarget: Int
            get() {
                val goalRoom = (newCardsGoal - newCardsToday).coerceAtLeast(0)
                val planned = dueToday + minOf(newToday, goalRoom)
                return if (planned == 0) dueToday + newToday else planned
            }
    }
    data class Error(val identity: PubkyIdentity?, val reason: ErrorReason) : HomeUiState
}

data class DeckSummary(
    val id: String,
    val title: String,
    /** Whose homeserver the deck lives on — not necessarily you, now that followed decks list here. */
    val authorPubky: String,
    val cardCount: Int,
    val dueCount: Int,
    /** Cards in this deck never graded. Shown when the deck has no reviews waiting. */
    val newCount: Int = 0,
    /** The author's cover emoji, or the title's initial when the deck carries none. */
    val coverEmoji: String,
    /** The deck's cover art, when it has one. Renders over [coverEmoji]; null falls back to it. */
    val coverImage: MediaRef.Image? = null,
)

sealed interface HomeEffect {
    data object NavigateCreateDeck : HomeEffect
    data object NavigateBrowseExamples : HomeEffect

    /** "See all" over today's decks — the full library, i.e. the Decks tab. */
    data object NavigateAllDecks : HomeEffect
    data object NavigateStartStudy : HomeEffect

    /** [authorPubky] is null only when the row could not name an author. */
    data class NavigateDeck(val deckId: String, val authorPubky: String? = null) : HomeEffect

    /** The stored session can no longer be used — the user must sign in again. */
    data object NavigateToOnboarding : HomeEffect
}
