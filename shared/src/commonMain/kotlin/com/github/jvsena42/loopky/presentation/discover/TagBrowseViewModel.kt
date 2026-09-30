package com.github.jvsena42.loopky.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckPage
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Tag
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

/**
 * Every deck on the network carrying one tag — where a tag chip goes when it is tapped outside
 * Discover (deck detail, a profile), per the design brief's rule that a tag leads to a
 * tag-filtered view.
 *
 * A screen with real modes, unlike Discover: there is one thing to load, so it is genuinely
 * loading, or empty, or showing content, or broken. Asks for more decks than Discover's strip
 * because this is the whole screen rather than one band of it.
 */
class TagBrowseViewModel(
    private val tag: Tag,
    private val discoveryRepository: DiscoveryRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<TagBrowseUiState>(TagBrowseUiState.Loading)
    val state: StateFlow<TagBrowseUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<TagBrowseEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<TagBrowseEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private val authors = mutableMapOf<String, PubkyIdentity>()

    val label: String get() = tag.value

    init {
        load()
    }

    fun onRetry() = load()

    /** Retries the page that failed, from its own cursor — the grid already on screen stays. */
    fun onRetryPage() {
        val content = _state.value as? TagBrowseUiState.Content ?: return
        if (content.pageError == null) return
        _state.update { if (it is TagBrowseUiState.Content) it.copy(pageError = null) else it }
        onEndReached()
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { TagBrowseUiState.Loading }
            val result = runSuspendCatching {
                discoveryRepository.decksByTagGlobalPage(tag, BROWSE_LIMIT, DeckPage.START)
            }.onFailure { Log.e(TAG, "load('${tag.value}'): FAILED — ${it.message}", it) }

            // "Offline" and "nobody tagged this" are different answers and this screen now tells
            // them apart — an unreachable indexer used to render as "No decks tagged X yet", which
            // is a claim about the network made by a device that never reached it (#321).
            result.exceptionOrNull()?.let { err ->
                _state.update { TagBrowseUiState.Error(err.toErrorReason()) }
                return@launch
            }

            val page = result.getOrThrow()
            Log.d(TAG, "load('${tag.value}'): ${page.decks.size} decks, hasMore=${page.hasMore}")
            _state.update {
                if (page.decks.isEmpty()) {
                    TagBrowseUiState.Empty
                } else {
                    TagBrowseUiState.Content(
                        decks = page.decks.toCards(authors),
                        cursor = page.nextCursor,
                        hasMore = page.hasMore,
                    )
                }
            }
            loadAuthorProfiles(page.decks)
        }
    }

    /**
     * The next page, appended. Driven from a scroll position, so it is called repeatedly and out of
     * order — [TagBrowseUiState.Content.canLoadMore] is the guard, alongside the job itself.
     */
    fun onEndReached() {
        val content = _state.value as? TagBrowseUiState.Content ?: return
        if (!content.canLoadMore || moreJob?.isActive == true) return
        val cursor = content.cursor
        _state.update { if (it is TagBrowseUiState.Content) it.copy(isLoadingMore = true) else it }

        moreJob = viewModelScope.launch {
            val page = runSuspendCatching {
                discoveryRepository.decksByTagGlobalPage(tag, BROWSE_LIMIT, cursor)
            }
                .onFailure { Log.e(TAG, "onEndReached('${tag.value}'): FAILED — ${it.message}", it) }
                // A failed page keeps the grid: this is a footer that could not load, not a screen
                // that could not load. `pageError` stops it retrying on every recomposition.
                .getOrElse { err ->
                    _state.update {
                        if (it is TagBrowseUiState.Content) {
                            it.copy(isLoadingMore = false, pageError = err.toErrorReason())
                        } else {
                            it
                        }
                    }
                    return@launch
                }

            _state.update { current ->
                if (current !is TagBrowseUiState.Content) {
                    current
                } else {
                    val shown = current.decks.mapTo(mutableSetOf()) { it.authorPubky + "/" + it.id }
                    current.copy(
                        decks = current.decks + page.decks.toCards(authors)
                            .filterNot { (it.authorPubky + "/" + it.id) in shown },
                        cursor = page.nextCursor,
                        hasMore = page.hasMore,
                        isLoadingMore = false,
                    )
                }
            }
            Log.d(TAG, "onEndReached('${tag.value}'): +${page.decks.size}, hasMore=${page.hasMore}")
            loadAuthorProfiles(page.decks)
        }
    }

    /** Names land after first paint, as everywhere else; a tile shows the pubky until then. */
    private suspend fun loadAuthorProfiles(decks: List<Deck>) {
        val pending = decks.map { it.authorPubky }.distinct().filterNot { it in authors }
        if (pending.isEmpty()) return
        val resolved = coroutineScope {
            pending.map { pubky -> async { identityRepository.fetchProfile(pubky).getOrNull() } }.awaitAll()
        }
        resolved.filterNotNull().forEach { authors[it.pubky] = it }
        if (resolved.all { it == null }) return
        _state.update { current ->
            if (current !is TagBrowseUiState.Content) {
                current
            } else {
                current.copy(
                    decks = current.decks.map { it.copy(author = authors[it.authorPubky] ?: it.author) },
                )
            }
        }
    }

    fun onOpenDeck(authorPubky: String, deckId: String) {
        viewModelScope.launch { _effects.emit(TagBrowseEffect.OpenDeck(authorPubky, deckId)) }
    }

    fun onOpenAuthor(pubky: String) {
        viewModelScope.launch { _effects.emit(TagBrowseEffect.OpenProfile(pubky)) }
    }

    companion object {
        private const val TAG = "Loopky/TagBrowseVM"

        /** A whole screen rather than one strip, so it takes the repository's own default. */
        internal const val BROWSE_LIMIT = TagRepository.DEFAULT_TAGGED_LIMIT
    }
}

sealed interface TagBrowseUiState {
    data object Loading : TagBrowseUiState
    data object Empty : TagBrowseUiState

    /**
     * [hasMore] comes from the repository rather than `decks.size == limit`: verification drops
     * entries, so a short page routinely has more behind it and a full one can be the last.
     */
    data class Content(
        val decks: List<DiscoverDeck>,
        val cursor: Int = DeckPage.START,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        /** A failed *page*, shown under the grid rather than instead of it. */
        val pageError: ErrorReason? = null,
    ) : TagBrowseUiState {
        val canLoadMore: Boolean get() = hasMore && !isLoadingMore && pageError == null
    }

    /**
     * The indexer did not answer. Distinct from [Empty], which is a claim about the network and is
     * only ever made after actually hearing from it.
     */
    data class Error(val reason: ErrorReason) : TagBrowseUiState
}

sealed interface TagBrowseEffect {
    data class OpenDeck(val authorPubky: String, val deckId: String) : TagBrowseEffect
    data class OpenProfile(val pubky: String) : TagBrowseEffect
}
