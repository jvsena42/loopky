package com.github.jvsena42.loopky.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.PubkyLink
import com.github.jvsena42.loopky.data.pubky.PubkyLinks
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One search box over the two things Loopky has to find: people and decks, by name or by pubky.
 *
 * Three answers can come back, and they are not alternatives to each other:
 *
 * 1. **A direct hit.** Anything that parses as an address — a bare pubky, a `pubky://` link, a
 *    whole shared message, a scanned QR — names exactly one profile or deck already. That needs no
 *    index and no network, so it resolves as the text is typed and sits above the results. It is
 *    also the only thing that reaches an account no indexer has seen yet, which is what makes
 *    pasting a friend's pubky still work on a young network.
 * 2. **People**, from the indexer's name and pubky-prefix lookups.
 * 3. **Decks**, matched on title, topic or author.
 *
 * Typing is not a query. Every keystroke would otherwise cost a network round trip and the
 * indexer would answer questions nobody finished asking, so the text is debounced and only the
 * latest search survives — [collectLatest] cancels the one in flight when a newer query lands.
 */
// Flow.debounce is still @FlowPreview in coroutines 1.10. The alternative — a hand-rolled
// job + delay — is the same behaviour with the cancellation logic written out by hand, which is
// the part worth not owning.
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val discoveryRepository: DiscoveryRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SearchEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SearchEffect> = _effects.asSharedFlow()

    /** What has been typed, before debouncing. Not the state's `query` — that one re-renders. */
    private val typed = MutableStateFlow("")

    /** Resolved author profiles, so a re-render reuses names already in hand. */
    private val authors = mutableMapOf<String, PubkyIdentity>()

    init {
        // Search is reachable from Discover, which a signed-out visitor gets in full — so the
        // results are readable either way and only the follow pill needs an account.
        viewModelScope.launch {
            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()
            _state.update { it.copy(isSignedIn = session != null) }
        }
        viewModelScope.launch {
            typed
                .debounce(DEBOUNCE_MILLIS)
                .map { it.trim() }
                // Trimming makes trailing spaces a no-op, and the pause before a query settles is
                // exactly when someone hits backspace and retypes the same letter.
                .distinctUntilChanged()
                .collectLatest { query -> search(query) }
        }
        viewModelScope.launch {
            discoveryRepository.blockChanges.collect { change ->
                if (!change.isBlocked) return@collect
                _state.update { current ->
                    current.copy(
                        people = current.people.filterNot { it.identity.pubky == change.pubky },
                        decks = current.decks.filterNot { it.authorPubky == change.pubky },
                    )
                }
            }
        }
    }

    fun onQueryChange(raw: String) {
        // Parsed on every keystroke rather than on the debounce: it is a local string test, and a
        // pasted link should offer itself immediately instead of after a pause.
        val link = PubkyLinks.parse(raw)
        val willSearch = link == null && raw.trim().length >= DiscoveryRepository.MIN_SEARCH_QUERY_LENGTH
        _state.update {
            it.copy(
                query = raw,
                directLink = link,
                isSearching = willSearch,
                // Results belong to the query that produced them; keeping them under new text
                // would show answers to a question that is no longer on screen.
                people = emptyList(),
                decks = emptyList(),
                hasSearched = false,
            )
        }
        typed.value = raw
    }

    fun onClearQuery() = onQueryChange("")

    /** The keyboard's search key: only an address can be acted on without waiting. */
    fun onSubmit() {
        val link = _state.value.directLink ?: return
        onOpenLink(link)
    }

    private suspend fun search(query: String) {
        if (query.length < DiscoveryRepository.MIN_SEARCH_QUERY_LENGTH) {
            _state.update { it.copy(isSearching = false, hasSearched = false) }
            return
        }
        // An address already names its one answer; searching the indexer for it could only
        // return the same account under a slower path, or nothing.
        if (PubkyLinks.parse(query) != null) {
            _state.update { it.copy(isSearching = false, hasSearched = false) }
            return
        }

        val (people, decks) = coroutineScope {
            val follows = async {
                runSuspendCatching { discoveryRepository.following() }
                    .onFailure { Log.w(TAG, "search: follows failed — ${it.message}") }
                    .getOrElse { emptyList() }
                    .toSet()
            }
            val people = async {
                runSuspendCatching { discoveryRepository.searchPeople(query) }
                    .onFailure { Log.e(TAG, "searchPeople('$query'): FAILED — ${it.message}", it) }
                    .getOrElse { emptyList() }
            }
            val decks = async {
                runSuspendCatching { discoveryRepository.searchDecks(query) }
                    .onFailure { Log.e(TAG, "searchDecks('$query'): FAILED — ${it.message}", it) }
                    .getOrElse { emptyList() }
            }
            val followed = follows.await()
            people.await().map { DiscoverPerson(it, isFollowing = it.pubky in followed) } to decks.await()
        }

        Log.d(TAG, "search('$query'): ${people.size} people, ${decks.size} decks")
        _state.update {
            // The box may have moved on while this was in flight; the query itself is the token,
            // because cancelling can miss a suspension point.
            if (it.query.trim() != query) {
                it
            } else {
                it.copy(
                    people = people,
                    decks = decks.toCards(authors),
                    isSearching = false,
                    hasSearched = true,
                )
            }
        }
        loadAuthorProfiles(decks)
    }

    /** Names land after the results do, exactly as on Discover; a tile shows the pubky until then. */
    private suspend fun loadAuthorProfiles(decks: List<Deck>) {
        val pending = decks.map { it.authorPubky }.distinct().filterNot { it in authors }
        if (pending.isEmpty()) return
        val resolved = coroutineScope {
            pending.map { pubky -> async { identityRepository.fetchProfile(pubky).getOrNull() } }.awaitAll()
        }
        resolved.filterNotNull().forEach { authors[it.pubky] = it }
        if (resolved.all { it == null }) return
        _state.update { current ->
            current.copy(decks = current.decks.map { it.copy(author = authors[it.authorPubky] ?: it.author) })
        }
    }

    /**
     * Follow straight from a result, so finding someone and following them is one screen rather
     * than two. Optimistic and reverted on failure, like the Discover strip.
     */
    fun onFollowToggle(pubky: String) {
        if (!_state.value.isSignedIn) {
            viewModelScope.launch { _effects.emit(SearchEffect.RequireSignIn(SignInReason.FollowPerson)) }
            return
        }
        val person = _state.value.people.firstOrNull { it.identity.pubky == pubky } ?: return
        if (person.isFollowPending) return
        val wasFollowing = person.isFollowing
        updatePerson(pubky) { it.copy(isFollowing = !wasFollowing, isFollowPending = true) }

        viewModelScope.launch {
            val result = if (wasFollowing) {
                discoveryRepository.unfollowUser(pubky)
            } else {
                discoveryRepository.followUser(pubky)
            }
            result
                .onSuccess { updatePerson(pubky) { it.copy(isFollowPending = false) } }
                .onFailure { err ->
                    Log.e(TAG, "onFollowToggle($pubky): FAILED — ${err.message}", err)
                    updatePerson(pubky) { it.copy(isFollowing = wasFollowing, isFollowPending = false) }
                    _effects.emit(SearchEffect.ShowFollowError(err.toErrorReason()))
                }
        }
    }

    fun onOpenProfile(pubky: String) {
        viewModelScope.launch { _effects.emit(SearchEffect.OpenProfile(pubky)) }
    }

    fun onOpenDeck(authorPubky: String, deckId: String) {
        viewModelScope.launch { _effects.emit(SearchEffect.OpenDeck(authorPubky, deckId)) }
    }

    /** Whatever the address resolved to — a deck link opens the deck, not its author. */
    fun onOpenLink(link: PubkyLink) {
        when (link) {
            is PubkyLink.Profile -> onOpenProfile(link.pubky)
            is PubkyLink.Deck -> onOpenDeck(link.pubky, link.deckId)
        }
    }

    private fun updatePerson(pubky: String, transform: (DiscoverPerson) -> DiscoverPerson) {
        _state.update { current ->
            current.copy(
                people = current.people.map { person ->
                    if (person.identity.pubky == pubky) transform(person) else person
                },
            )
        }
    }

    companion object {
        private const val TAG = "Loopky/SearchVM"

        /**
         * Long enough to swallow a burst of typing, short enough that stopping to look at the
         * screen returns an answer rather than a wait.
         */
        internal const val DEBOUNCE_MILLIS = 350L
    }
}

/**
 * A single data class rather than a sealed hierarchy: the three answers coexist. A pasted link can
 * be on screen while people are still resolving, and requiring each write to first prove the
 * screen is in some `Content` case is what produced stale-write races on Discover.
 */
data class SearchUiState(
    /** False while nobody is signed in: results still resolve, but Follow raises a prompt. */
    val isSignedIn: Boolean = true,
    val query: String = "",
    val isSearching: Boolean = false,
    /** True once a query has settled, which is what separates "no matches" from "not asked yet". */
    val hasSearched: Boolean = false,
    /** The one thing the typed text already names, when it is an address rather than a query. */
    val directLink: PubkyLink? = null,
    val people: List<DiscoverPerson> = emptyList(),
    val decks: List<DiscoverDeck> = emptyList(),
) {
    /** Settled with nothing at all — the only case that draws the no-matches block. */
    val isEmpty: Boolean
        get() = hasSearched && !isSearching && directLink == null && people.isEmpty() && decks.isEmpty()
}

sealed interface SearchEffect {
    data class OpenProfile(val pubky: String) : SearchEffect
    data class OpenDeck(val authorPubky: String, val deckId: String) : SearchEffect

    /** A follow that failed — the pill has already reverted, so this only explains why. */
    data class ShowFollowError(val reason: ErrorReason) : SearchEffect

    /** A guest reached for Follow. [reason] is what the prompt is about. */
    data class RequireSignIn(val reason: SignInReason) : SearchEffect
}
