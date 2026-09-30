package com.github.jvsena42.loopky.presentation.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DecksLibraryViewModel(
    private val deckRepository: DeckRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<DecksLibraryUiState>(DecksLibraryUiState.Loading)
    val state: StateFlow<DecksLibraryUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DecksLibraryEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DecksLibraryEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    /** Resolved author profiles, keyed by pubky. Survives a reload so names don't flicker back. */
    private val authors = mutableMapOf<String, PubkyIdentity>()

    init {
        load()
        // Publish and delete run on other destinations while this tab stays composed, so
        // without this the grid keeps showing "No decks yet" right after a publish, and keeps
        // listing a deck that was just deleted.
        viewModelScope.launch {
            deckRepository.changes.collect { load(silent = true) }
        }
    }

    fun onRefresh() = load()

    /** [silent] keeps existing content on screen while a background refresh runs. */
    private fun load(silent: Boolean = false) {
        // Cancel rather than bail out: a change that lands mid-load must not be dropped.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: fetching decks (silent=$silent)")
            if (!silent) paintFromCache()
            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val myIdentity = session?.identity

            runSuspendCatching { deckRepository.listOwned() }
                .onSuccess { owned ->
                    // Followed decks live on other people's homeservers, so they fail
                    // independently. Losing them must not turn a working library into an error.
                    val followed = runSuspendCatching { deckRepository.listFollowed() }
                        .onFailure { Log.e(TAG, "load: followed decks unavailable — ${it.message}", it) }
                        .getOrDefault(emptyList())
                    val decks = (owned + followed).distinctBy { it.id }

                    if (decks.isEmpty()) {
                        _state.update { DecksLibraryUiState.Empty }
                    } else {
                        val followedIds = followed.mapTo(mutableSetOf()) { it.id }
                        val updatedIds = followed
                            .filter { deckRepository.hasUpdate(it.id) }
                            .mapTo(mutableSetOf()) { it.id }
                        _state.update { DecksLibraryUiState.Content(
                            deckCount = decks.size,
                            decks = decks.map {
                                it.toTileModel(myIdentity, it.id in followedIds, it.id in updatedIds)
                            },
                        ) }
                        loadAuthorProfiles(decks, myIdentity?.pubky)
                    }
                    Log.d(TAG, "load: owned=${owned.size} followed=${followed.size}")
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.update { DecksLibraryUiState.Error(reason = err.toErrorReason()) }
                }
        }
    }

    /**
     * Show the library this device last saw, so opening this tab is not a spinner over content that
     * has not changed since the last launch. Replaced by the load already running behind it.
     *
     * The "updated" dots start off: whether a followed author has published since is a comparison
     * against a subscription record this has not read, and a dot that appears on every deck and
     * then vanishes is worse than one that arrives a moment late.
     */
    private suspend fun paintFromCache() {
        val cached = runSuspendCatching { deckRepository.listCached() }.getOrNull()
        val decks = cached?.let { (it.owned + it.followed).distinctBy { deck -> deck.id } }
        if (decks.isNullOrEmpty()) {
            _state.update { DecksLibraryUiState.Loading }
            return
        }
        val myIdentity = runSuspendCatching { identityRepository.currentSession() }
            .getOrNull()?.identity
        val followedIds = cached.followed.mapTo(mutableSetOf()) { it.id }
        _state.update {
            DecksLibraryUiState.Content(
                deckCount = decks.size,
                decks = decks.map {
                    it.toTileModel(myIdentity, it.id in followedIds, hasUpdate = false)
                },
            )
        }
        // Launched, never awaited: this runs one profile GET per distinct author, and `load()`
        // waits on this function before it asks the homeserver for anything. Awaited, the cached
        // paint would be followed by a stall on the slowest of those — a network round trip added
        // to the very path this cache exists to shorten.
        viewModelScope.launch { loadAuthorProfiles(decks, myIdentity?.pubky) }
    }

    fun onQueryChanged(query: String) {
        _state.update { s -> if (s is DecksLibraryUiState.Content) s.copy(query = query) else s }
    }

    fun onSortChanged(sort: DeckSort) {
        _state.update { s -> if (s is DecksLibraryUiState.Content) s.copy(sort = sort) else s }
    }

    fun onDeckClick(deckId: String) {
        // The author travels with the deck id: for a followed deck it is the only way deck detail
        // can fetch the manifest on a cold cache, since it lives on someone else's homeserver.
        val author = (_state.value as? DecksLibraryUiState.Content)
            ?.decks?.firstOrNull { it.id == deckId }?.author?.pubky
        viewModelScope.launch {
            _effects.emit(DecksLibraryEffect.NavigateDeckDetail(deckId, author))
        }
    }

    fun onImportClick() {
        viewModelScope.launch { _effects.emit(DecksLibraryEffect.NavigateImport) }
    }

    fun onCreateDeckClick() {
        viewModelScope.launch { _effects.emit(DecksLibraryEffect.NavigateCreateDeck) }
    }

    /**
     * Owner names arrive after first paint: a followed deck's author lives on someone else's
     * homeserver, so its tile shows the bare pubky until that profile lands — and an author who
     * published no profile keeps it.
     */
    private suspend fun loadAuthorProfiles(decks: List<Deck>, myPubky: String?) {
        val pending = decks
            .map { it.authorPubky }
            .distinct()
            .filterNot { it == myPubky || it in authors }
        if (pending.isEmpty()) return
        val resolved = coroutineScope {
            pending.map { pubky -> async { identityRepository.fetchProfile(pubky).getOrNull() } }
                .awaitAll()
        }
        resolved.filterNotNull().forEach { authors[it.pubky] = it }
        if (resolved.all { it == null }) return
        _state.update { s ->
            if (s !is DecksLibraryUiState.Content) {
                s
            } else {
                s.copy(
                    decks = s.decks.map { tile ->
                        authors[tile.author.pubky]?.let { tile.copy(author = it) } ?: tile
                    },
                )
            }
        }
        Log.d(TAG, "loadAuthorProfiles: resolved=${resolved.count { it != null }}/${pending.size}")
    }

    /**
     * Naming the author is the platform layer's job; this only carries who it is. A deck someone
     * else wrote resolves through [loadAuthorProfiles]; until then it is the bare pubky.
     */
    private fun Deck.toTileModel(
        myIdentity: PubkyIdentity?,
        isFollowed: Boolean,
        hasUpdate: Boolean,
    ): DeckTileModel {
        val isOwned = authorPubky == myIdentity?.pubky
        return DeckTileModel(
            id = id,
            title = title,
            cardCount = cardCount,
            coverEmoji = coverEmoji ?: title.firstOrNull()?.uppercaseChar()?.toString() ?: "📚",
            coverImage = coverImageRef,
            author = myIdentity?.takeIf { isOwned }
                ?: authors[authorPubky]
                ?: PubkyIdentity(authorPubky, displayName = null, avatarUrl = null, bio = null),
            relation = when {
                isFollowed -> DeckRelation.Followed
                isOwned && source?.kind == DeckSource.Kind.Clone -> DeckRelation.Cloned
                isOwned -> DeckRelation.Owned
                else -> DeckRelation.None
            },
            // Only ever true for a followed deck: your own edits are not news to you.
            hasUpdate = hasUpdate,
            updatedAt = updatedAt,
        )
    }

    companion object {
        private const val TAG = "Loopky/DecksLibVM"
    }
}

sealed interface DecksLibraryUiState {
    data object Loading : DecksLibraryUiState
    data object Empty : DecksLibraryUiState
    data class Content(
        val deckCount: Int,
        val decks: List<DeckTileModel>,
        val query: String = "",
        val sort: DeckSort = DeckSort.Recent,
    ) : DecksLibraryUiState {
        /**
         * Filtering and sorting happen over the already-loaded list — the library is small and
         * Pubky has no query API, so there is nothing to gain from a round trip.
         */
        val visibleDecks: List<DeckTileModel>
            get() = decks
                .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
                .let { filtered ->
                    when (sort) {
                        DeckSort.Recent -> filtered.sortedByDescending { it.updatedAt }
                        DeckSort.Alphabetical -> filtered.sortedBy { it.title.lowercase() }
                        DeckSort.CardCount -> filtered.sortedByDescending { it.cardCount }
                    }
                }
    }
    data class Error(val reason: ErrorReason) : DecksLibraryUiState
}

enum class DeckSort { Recent, Alphabetical, CardCount }

/**
 * How the signed-in user relates to a deck. Replaces a bare `isOwned` flag: since #33 a library tile
 * can be a deck you wrote, one you follow, or a copy you made of someone else's — and they behave
 * differently enough (editable? receives the author's updates?) that the tile has to say which.
 */
enum class DeckRelation {
    /** Yours, written here. */
    Owned,

    /** Someone else's; you hold a subscription and receive their updates. Read-only. */
    Followed,

    /** Yours, forked from someone else's. Editable, and it never receives the original's updates. */
    Cloned,

    /** Neither — a deck being browsed rather than kept. */
    None,
}

data class DeckTileModel(
    val id: String,
    val title: String,
    val cardCount: Int,
    val coverEmoji: String,
    /** The deck's cover art, when it has one. Renders over [coverEmoji]; null falls back to it. */
    val coverImage: MediaRef.Image? = null,
    val author: PubkyIdentity,
    val relation: DeckRelation,
    /** The author has published changes since you last opened this. Followed decks only. */
    val hasUpdate: Boolean = false,
    val updatedAt: Long,
) {
    val isOwned: Boolean get() = relation == DeckRelation.Owned || relation == DeckRelation.Cloned
}

sealed interface DecksLibraryEffect {
    /** [authorPubky] is null only when the tile could not name an author. */
    data class NavigateDeckDetail(
        val deckId: String,
        val authorPubky: String? = null,
    ) : DecksLibraryEffect
    data object NavigateImport : DecksLibraryEffect
    data object NavigateCreateDeck : DecksLibraryEffect
}
