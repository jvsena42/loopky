package com.github.jvsena42.loopky.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckPage
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.PeoplePage
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.presentation.auth.SignInReason
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
 * Discover, as strips that load independently after first paint: deck topics, a global browse of
 * everything published, and decks from people the user follows.
 *
 * Following nobody is a normal state, not an empty one — the screen used to collapse to "follow a
 * friend to see their decks here", which left a new account with nothing to do unless it already
 * knew someone's pubky (#26). Global browse comes from the Nexus indexer and needs no follow
 * relationship, so it carries the screen on its own.
 *
 * No strip gates another and none of them can blank the screen: each owns its loading, empty and
 * error state, and every loader catches its own failure so a sibling cannot take the rest down.
 */
// Over the function ceiling because Discover is four independent strips on one screen, and each
// owns its own entry points — load, retry, page. Splitting it would mean splitting the state they
// share, which is what the single [DiscoverUiState] exists to avoid.
@Suppress("TooManyFunctions")
class DiscoverViewModel(
    private val discoveryRepository: DiscoveryRepository,
    private val tagRepository: TagRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DiscoverEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DiscoverEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var browseJob: Job? = null
    private var browseMoreJob: Job? = null
    private var peopleMoreJob: Job? = null

    /** Every deck browse has fetched this session — the seed authors a people page can reach. */
    private var browsedDecks: List<Deck> = emptyList()

    /**
     * Tiles per page of global browse. Set by whichever platform is drawing the grid — see
     * [onGridColumnsChanged].
     */
    private var browsePageSize = BROWSE_LIMIT

    /** The followed feed, kept so selecting a topic re-filters that strip with no round-trip. */
    private var feed: List<Deck> = emptyList()

    /** Global topics, kept apart from feed labels so either source can land first. */
    private var globalTopics: List<Tag> = emptyList()

    /** Resolved author profiles, so a re-render reuses names already in hand. */
    private val authors = mutableMapOf<String, PubkyIdentity>()

    init {
        load()
    }

    fun onRefresh() = load(isRefresh = true)

    /**
     * Tell the ViewModel how wide the deck grid is, so a page is a screenful wherever it is drawn.
     *
     * The width class itself stays in the platform layer — it is a UI concern, and it changes while
     * the app is running on a tablet that rotates or goes split-screen. Only the resulting column
     * count crosses, and only pages loaded *after* the change use the new size: re-fetching what is
     * already on screen because the device turned would throw away the reader's place.
     */
    fun onGridColumnsChanged(columns: Int) {
        browsePageSize = (columns * BROWSE_ROWS).coerceIn(BROWSE_LIMIT, MAX_BROWSE_LIMIT)
    }

    private fun load(isRefresh: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val tags = _state.value.selectedTags
            // Discover is the one screen a signed-out visitor gets in full, so the session is
            // resolved here rather than assumed: everything below reads public records, and the
            // only thing an account changes is whether the followed strip and the follow pills
            // mean anything.
            val signedIn = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val isSignedIn = signedIn != null
            _state.update {
                it.copy(
                    isSignedIn = isSignedIn,
                    topics = it.topics.loading(),
                    people = it.people.loading(),
                    browse = it.browse.loading(),
                    pendingTopics = it.visibleTopics,
                    // Not "loading" for a guest: there is no follow graph to read, and a spinner
                    // that can only ever settle empty is a strip promising something it has none of.
                    following = if (isSignedIn) it.following.loading() else it.following.loaded(emptyList()),
                    isRefreshing = isRefresh,
                )
            }
            coroutineScope {
                if (isSignedIn) launch { loadFollowing() }
                launch { loadTopics() }
                // People is seeded from what browse found, so it chains off it rather than
                // racing it. Everything else runs alongside.
                launch { loadPeople(seed = loadBrowse(tags)) }
            }
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * The only strip whose repository throws, so the only one that can show an error. An
     * unreachable homeserver must not read as "you follow nobody" — see
     * [DiscoveryRepository.decksFromFollowing].
     */
    private suspend fun loadFollowing() {
        runSuspendCatching { discoveryRepository.decksFromFollowing() }
            .onSuccess { decks ->
                feed = decks
                _state.update {
                    it.copy(
                        following = it.following.loaded(decks.filterByTags(it.selectedTags).toCards(authors)),
                        topics = it.topics.copy(items = mergedTopics(globalTopics, feed)),
                    )
                }
                Log.d(TAG, "loadFollowing: ${decks.size} decks")
                loadAuthorProfiles(decks)
            }
            .onFailure { err ->
                Log.e(TAG, "loadFollowing: FAILED — ${err.message}", err)
                _state.update { it.copy(following = it.following.failed(err.toErrorReason())) }
            }
    }

    /**
     * Global browse. No [tags] browses every published deck via [ReservedTags.DECK]; otherwise it
     * asks the indexer for decks carrying all of them rather than filtering what is already on
     * screen, because a network-wide topic almost never matches the handful of decks in the
     * followed feed.
     */
    private suspend fun loadBrowse(tags: List<Tag>): List<Deck> {
        val label = tags.describe()
        val result = runSuspendCatching { fetchBrowsePage(tags, DeckPage.START) }

        val error = result.exceptionOrNull()
        if (error != null) {
            // The strip reports it rather than settling empty. An unreachable indexer used to render
            // as "Nothing published here yet" — a confident claim about the world, made by a device
            // that had not heard from it, with no retry offered (#321).
            Log.e(TAG, "loadBrowse('$label'): FAILED — ${error.message}", error)
            _state.update { current ->
                if (current.selectedTags != tags) {
                    current
                } else {
                    current.copy(browse = current.browse.failed(error.toErrorReason()))
                }
            }
            return emptyList()
        }

        val page = result.getOrThrow()
        _state.update { current ->
            // A newer selection may have landed while this was in flight; cancelling the job can
            // miss a suspension point, so the selection itself is the token.
            if (current.selectedTags != tags) {
                current
            } else {
                current.copy(
                    browse = current.browse.loaded(
                        items = page.decks.toCards(authors),
                        cursor = page.nextCursor,
                        hasMore = page.hasMore,
                    ),
                )
            }
        }
        Log.d(TAG, "loadBrowse('$label'): ${page.decks.size} decks, hasMore=${page.hasMore}")
        loadAuthorProfiles(page.decks)
        return page.decks
    }

    /**
     * Retries the *page* that failed, from the cursor it failed at — the decks already on screen
     * stay. Clearing [SectionState.pageError] is what re-opens [SectionState.canLoadMore].
     */
    fun onRetryBrowsePage() {
        if (_state.value.browse.pageError == null) return
        _state.update { it.copy(browse = it.browse.copy(pageError = null)) }
        onBrowseEndReached()
    }

    /** Retries global browse alone, at the first page — the other strips are unaffected. */
    fun onRetryBrowse() {
        if (_state.value.browse.isLoading) return
        browseJob?.cancel()
        browseMoreJob?.cancel()
        val tags = _state.value.selectedTags
        _state.update { it.copy(browse = it.browse.loading(), pendingTopics = it.visibleTopics) }
        browseJob = viewModelScope.launch { loadBrowse(tags) }
    }

    /**
     * The next page of global browse. The reader is already looking at the grid, so this appends
     * under it rather than going back through [SectionState.loading] — see [SectionState].
     *
     * Called from a scroll position, which means it is called repeatedly and out of order.
     * [SectionState.canLoadMore] is the whole guard: no page in flight, no error standing, and the
     * repository has said there is more.
     */
    fun onBrowseEndReached() {
        if (!_state.value.browse.canLoadMore || browseMoreJob?.isActive == true) return
        val tags = _state.value.selectedTags
        val label = tags.describe()
        val cursor = _state.value.browse.cursor
        _state.update { it.copy(browse = it.browse.copy(isLoadingMore = true)) }

        browseMoreJob = viewModelScope.launch {
            val page = runSuspendCatching { fetchBrowsePage(tags, cursor) }
                .onFailure { Log.e(TAG, "onBrowseEndReached('$label'): FAILED — ${it.message}", it) }

            page.exceptionOrNull()?.let { err ->
                // A failed *page* keeps the decks already on screen and puts the error under them:
                // this is a footer that could not load, not a strip that could not load. Clearing
                // `isLoadingMore` matters most — without it the footer spins forever.
                _state.update { current ->
                    if (current.selectedTags != tags) {
                        current
                    } else {
                        current.copy(browse = current.browse.pageFailed(err.toErrorReason()))
                    }
                }
                return@launch
            }

            _state.update { current ->
                // Same token as the first page: a topic chosen mid-flight makes this page answer a
                // question nobody is asking any more, and appending it would mix two browses.
                if (current.selectedTags != tags) {
                    current
                } else {
                    current.copy(
                        browse = current.browse.appended(
                            more = page.getOrThrow().decks.toCards(authors),
                            cursor = page.getOrThrow().nextCursor,
                            hasMore = page.getOrThrow().hasMore,
                        ),
                    )
                }
            }
            val decks = page.getOrThrow().decks
            Log.d(TAG, "onBrowseEndReached('$label'): +${decks.size}")
            loadAuthorProfiles(decks)
            // Browse is also where the people strip gets its seed authors, so a new page of decks
            // widens the candidate roll the next page of people walks.
            browsedDecks = browsedDecks + decks
        }
    }

    /**
     * One page of browse for [tags], from [cursor].
     *
     * The indexer is asked for the *last* tag chosen and the rest are checked against each manifest
     * (see [DiscoveryRepository.decksByTagGlobalPage]): drilling down usually picks the narrow tag
     * last, and the choice depends on the selection alone, so every page of one browse walks the
     * same index — a cursor into one tag's index is meaningless in another's.
     *
     * A filtered page can come back empty with more behind it, and neither platform's footer asks
     * again while it stays on screen, so an empty filtered page is followed straight on — up to
     * [MAX_EMPTY_FILTERED_PAGES] times. Unfiltered browse keeps its single read per page.
     */
    private suspend fun fetchBrowsePage(tags: List<Tag>, cursor: Int): DeckPage {
        val alsoTagged = tags.dropLast(1).toSet()
        var page = discoveryRepository.decksByTagGlobalPage(tags.browseTag(), browsePageSize, cursor, alsoTagged)
        if (alsoTagged.isEmpty()) return page
        repeat(MAX_EMPTY_FILTERED_PAGES) {
            if (page.decks.isNotEmpty() || !page.hasMore) return page
            page = discoveryRepository.decksByTagGlobalPage(
                tags.browseTag(),
                browsePageSize,
                page.nextCursor,
                alsoTagged,
            )
        }
        return page
    }

    /**
     * People to follow, seeded with whatever browse found: the indexer's `loopky-user` directory
     * comes back empty for a young label, so deck authors are the source that actually works — see
     * [DiscoveryRepository.suggestedPeople].
     */
    private suspend fun loadPeople(seed: List<Deck>) {
        browsedDecks = seed
        val page = runSuspendCatching {
            discoveryRepository.suggestedPeoplePage(seed, PEOPLE_LIMIT, PeoplePage.START)
        }
            .onFailure { Log.e(TAG, "loadPeople: FAILED — ${it.message}", it) }
            .getOrElse { PeoplePage(emptyList(), PeoplePage.START, hasMore = false) }
        _state.update {
            it.copy(
                people = it.people.loaded(
                    items = page.people.map(::DiscoverPerson),
                    cursor = page.nextCursor,
                    hasMore = page.hasMore,
                ),
            )
        }
        Log.d(TAG, "loadPeople: ${page.people.size} suggestions, hasMore=${page.hasMore}")
    }

    /**
     * The next page of suggestions, appended to the carousel the reader is already scrolling.
     *
     * Handed [browsedDecks] rather than only the first page's seed, so authors turned up by a later
     * browse page can be suggested too — the repository appends them to its candidate roll and the
     * cursor keeps indexing the same list.
     */
    fun onPeopleEndReached() {
        if (!_state.value.people.canLoadMore || peopleMoreJob?.isActive == true) return
        val cursor = _state.value.people.cursor
        val seed = browsedDecks
        _state.update { it.copy(people = it.people.copy(isLoadingMore = true)) }

        peopleMoreJob = viewModelScope.launch {
            val page = runSuspendCatching {
                discoveryRepository.suggestedPeoplePage(seed, PEOPLE_LIMIT, cursor)
            }
                .onFailure { Log.e(TAG, "onPeopleEndReached: FAILED — ${it.message}", it) }
                .getOrElse { PeoplePage(emptyList(), cursor, hasMore = false) }

            _state.update { current ->
                // Deduped on the pubky: the roll is append-only, but a deck author already shown
                // from the directory can arrive again as a seed author.
                val shown = current.people.items.mapTo(mutableSetOf()) { it.identity.pubky }
                current.copy(
                    people = current.people.appended(
                        more = page.people.filterNot { it.pubky in shown }.map(::DiscoverPerson),
                        cursor = page.nextCursor,
                        hasMore = page.hasMore,
                    ),
                )
            }
            Log.d(TAG, "onPeopleEndReached: +${page.people.size}, hasMore=${page.hasMore}")
        }
    }

    private suspend fun loadTopics() {
        globalTopics = runSuspendCatching { tagRepository.trendingDeckTags() }
            .onFailure { Log.e(TAG, "loadTopics: FAILED — ${it.message}", it) }
            .getOrElse { emptyList() }
        _state.update { it.copy(topics = it.topics.loaded(mergedTopics(globalTopics, feed))) }
    }

    /**
     * Author names arrive after first paint: a tile shows the bare pubky until its author's
     * profile lands, and an author with no published profile simply keeps it.
     */
    private suspend fun loadAuthorProfiles(decks: List<Deck>) {
        val pending = decks.map { it.authorPubky }.distinct().filterNot { it in authors }
        if (pending.isEmpty()) return
        val resolved = coroutineScope {
            pending.map { pubky -> async { identityRepository.fetchProfile(pubky).getOrNull() } }.awaitAll()
        }
        resolved.filterNotNull().forEach { authors[it.pubky] = it }
        if (resolved.all { it == null }) return
        _state.update { it.withResolvedAuthors(authors) }
        Log.d(TAG, "loadAuthorProfiles: resolved=${resolved.count { it != null }}/${pending.size}")
    }

    /**
     * Select by label. **This is the entry point Swift must use.**
     *
     * [Tag] is a `value class`, and Kotlin/Native treats one differently either side of a call:
     * boxed as an element of a `List<Tag>`, but *erased to its underlying `String`* at a parameter
     * position. Swift can only obtain a `Tag` as the boxed object it finds in state, so handing
     * that object to [onTagSelected] passed a Kotlin object where the bridge expected an
     * `NSString`. Nothing rejected it: the pointer was reinterpreted, `value` read back null, and
     * `sanitizeLabel` segfaulted on `trim()` — a null dereference in a language with no nulls,
     * reachable by tapping a topic chip on Discover.
     *
     * A `String` crosses as itself, so the erasure has nothing to disagree about. Prefer this
     * anywhere a `Tag` would otherwise cross the ObjC boundary as an argument.
     */
    fun onTagLabelSelected(label: String?) = onTagSelected(label?.let(::Tag))

    /**
     * Toggles [tag] in the selection; null clears it. Several tags narrow browse and the followed
     * strip to decks carrying **all** of them.
     */
    fun onTagSelected(tag: Tag?) {
        val current = _state.value.selectedTags
        val next = when (tag) {
            null -> emptyList()
            in current -> current - tag
            else -> current + tag
        }
        val before = _state.value
        val decksOnScreen = before.browse.items + before.following.items
        // Until the new page lands, the row is narrowed from what is already on screen when a tag
        // was added — a subset of those decks is all the new selection can match — and otherwise
        // stays as it was, so a dropped chip does not vanish under the finger.
        val pending = if (tag != null && tag !in current && decksOnScreen.isNotEmpty()) {
            before.copy(selectedTags = next).narrowedTopics(decksOnScreen)
        } else {
            before.visibleTopics
        }
        browseJob?.cancel()
        // A page in flight for the previous topic would append someone else's decks under this
        // one's header. The selection token in [onBrowseEndReached] catches it too; cancelling
        // here is what stops the request being paid for at all.
        browseMoreJob?.cancel()
        _state.update {
            it.copy(
                selectedTags = next,
                pendingTopics = pending,
                browse = SectionState(isLoading = true),
                following = it.following.copy(items = feed.filterByTags(next).toCards(authors)),
            )
        }
        browseJob = viewModelScope.launch { loadBrowse(next) }
    }

    /** Retries the followed feed alone — the other strips degrade to empty rather than error. */
    fun onRetryFollowing() {
        if (!_state.value.isSignedIn) return
        viewModelScope.launch {
            _state.update { it.copy(following = it.following.loading()) }
            loadFollowing()
        }
    }

    /**
     * Follow straight from the suggestions strip. Optimistic so the pill responds immediately, and
     * reverted on failure — the person stays on the strip either way, since a failed follow is
     * worth retrying rather than hiding.
     */
    fun onFollowToggle(pubky: String) {
        // On the action, not only on the pill. A guest sees the strip in full — the suggestions
        // are worth seeing, and they are what a visitor is here for — but following writes a
        // record into a follow graph that does not exist yet.
        if (!_state.value.isSignedIn) {
            viewModelScope.launch { _effects.emit(DiscoverEffect.RequireSignIn(SignInReason.FollowPerson)) }
            return
        }
        val person = _state.value.people.items.firstOrNull { it.identity.pubky == pubky } ?: return
        if (person.isFollowPending) return
        val wasFollowing = person.isFollowing
        _state.updatePerson(pubky) { it.copy(isFollowing = !wasFollowing, isFollowPending = true) }

        viewModelScope.launch {
            val result = if (wasFollowing) {
                discoveryRepository.unfollowUser(pubky)
            } else {
                discoveryRepository.followUser(pubky)
            }
            result
                .onSuccess { _state.updatePerson(pubky) { it.copy(isFollowPending = false) } }
                .onFailure { err ->
                    Log.e(TAG, "onFollowToggle($pubky): FAILED — ${err.message}", err)
                    _state.updatePerson(pubky) { it.copy(isFollowing = wasFollowing, isFollowPending = false) }
                    _effects.emit(DiscoverEffect.ShowFollowError(err.toErrorReason()))
                }
        }
    }

    fun onSearch() {
        viewModelScope.launch { _effects.emit(DiscoverEffect.OpenSearch) }
    }

    fun onOpenAuthor(pubky: String) {
        viewModelScope.launch { _effects.emit(DiscoverEffect.OpenProfile(pubky)) }
    }

    fun onOpenDeck(authorPubky: String, deckId: String) {
        viewModelScope.launch { _effects.emit(DiscoverEffect.OpenDeck(authorPubky, deckId)) }
    }

    companion object {
        private const val TAG = "Loopky/DiscoverVM"

        /**
         * How many grid rows one page of global browse is worth.
         *
         * A page is counted in *rows*, not tiles, because the tile count that fills a screen is a
         * property of the screen: twelve tiles is six rows on a phone and three on a 4-column
         * tablet, where it lands barely past the fold and every reader pays a round-trip
         * immediately. Six rows is roughly two screenfuls at any width.
         */
        internal const val BROWSE_ROWS = 6

        /**
         * The page size before any platform has reported its grid, and the floor under
         * [onGridColumnsChanged]. Global browse costs one manifest fetch per deck, four at a time,
         * each against a different homeserver — twelve is three waves; the repository default of
         * thirty would be eight before anything renders.
         */
        internal const val BROWSE_LIMIT = 12

        /** No grid is wider than this, so nothing can ask for an unbounded page. */
        internal const val MAX_BROWSE_LIMIT = 30

        /**
         * Each candidate costs a self-tag check plus a profile fetch, and the indexer clamps the
         * directory read to twenty. Ten keeps the strip inside five waves.
         */
        internal const val PEOPLE_LIMIT = 10

        /**
         * How many further pages one filtered browse page may read past an empty one. Each is up to
         * four indexer reads plus a manifest fetch per candidate, so this bounds a filter nothing
         * matches at a dozen or so reads rather than the whole tag index.
         */
        internal const val MAX_EMPTY_FILTERED_PAGES = 2
    }
}

private const val FALLBACK_EMOJI = "📚"

internal fun bareIdentity(pubky: String) =
    PubkyIdentity(pubky, displayName = null, avatarUrl = null, bio = null)

/** Re-renders both deck strips with any author names that have since resolved. */
private fun DiscoverUiState.withResolvedAuthors(authors: Map<String, PubkyIdentity>): DiscoverUiState {
    fun DiscoverDeck.resolved() = copy(author = authors[authorPubky] ?: author)
    return copy(
        browse = browse.copy(items = browse.items.map { it.resolved() }),
        following = following.copy(items = following.items.map { it.resolved() }),
    )
}

private fun MutableStateFlow<DiscoverUiState>.updatePerson(
    pubky: String,
    transform: (DiscoverPerson) -> DiscoverPerson,
) = update { current ->
    current.copy(
        people = current.people.copy(
            items = current.people.items.map { person ->
                if (person.identity.pubky == pubky) transform(person) else person
            },
        ),
    )
}

/**
 * Global topics first — they are ranked — then labels only the followed feed knows about.
 *
 * A pure function of its two arguments, so it lives here beside the other list helpers rather
 * than on the ViewModel, which is at detekt's function ceiling.
 */
private fun mergedTopics(globalTopics: List<Tag>, feed: List<Deck>): List<Tag> =
    (globalTopics + feed.flatMap { it.tags })
        .filterNot { ReservedTags.isReserved(it) }
        .distinct()

private fun List<Deck>.filterByTags(tags: List<Tag>): List<Deck> =
    if (tags.isEmpty()) this else filter { it.tags.containsAll(tags) }

/** The tag browse asks the indexer for — see `fetchBrowsePage` for why the last one. */
private fun List<Tag>.browseTag(): Tag = lastOrNull() ?: ReservedTags.DECK

private fun List<Tag>.describe(): String = ifEmpty { listOf(ReservedTags.DECK) }.joinToString(" + ") { it.value }

internal fun List<Deck>.toCards(authors: Map<String, PubkyIdentity>): List<DiscoverDeck> = map { deck ->
    DiscoverDeck(
        id = deck.id,
        authorPubky = deck.authorPubky,
        title = deck.title,
        cardCount = deck.cardCount,
        coverEmoji = deck.coverEmoji ?: deck.title.firstOrNull()?.uppercaseChar()?.toString() ?: FALLBACK_EMOJI,
        coverImage = deck.coverImageRef,
        author = authors[deck.authorPubky] ?: bareIdentity(deck.authorPubky),
        tags = deck.tags.map { it.value },
    )
}

/**
 * Discover's strips. A single state rather than a sealed hierarchy because the screen has no
 * modes: the strips coexist and settle at different times, and requiring each write to first prove
 * the screen is in some `Content` case is what produced the stale-write races this replaced.
 */
data class DiscoverUiState(
    /**
     * False while the visitor has no account. Discover works either way — every read on it is
     * public — so this changes only what is *offered*: the followed strip goes, and the follow
     * pills raise a sign-in prompt instead of writing.
     */
    val isSignedIn: Boolean = true,
    val topics: SectionState<Tag> = SectionState(),
    val people: SectionState<DiscoverPerson> = SectionState(),
    val browse: SectionState<DiscoverDeck> = SectionState(),
    val following: SectionState<DiscoverDeck> = SectionState(),
    /** In the order they were chosen. Empty means unfiltered. */
    val selectedTags: List<Tag> = emptyList(),
    /** The topic row while a filtered browse is loading or failed — see [visibleTopics]. */
    val pendingTopics: List<Tag> = emptyList(),
    val isRefreshing: Boolean = false,
) {
    /**
     * The topic chips to draw. Unfiltered, every topic. With a selection, only the chosen tags and
     * the tags carried by decks on screen that match all of them — so no chip leads to an empty
     * browse. Nexus cannot answer "which labels co-occur with these" (pubky/pubky-nexus#1073), so
     * the row is only as complete as the pages loaded, and grows as more land.
     */
    val visibleTopics: List<Tag>
        get() = when {
            selectedTags.isEmpty() -> topics.items
            browse.isLoading || browse.error != null -> pendingTopics
            else -> narrowedTopics(browse.items + following.items)
        }

    /**
     * Chosen tags first, then co-occurring ones in trending order — stable as pages land, where a
     * per-page count would reshuffle the row under the reader's finger — then the rest by label.
     */
    internal fun narrowedTopics(decks: List<DiscoverDeck>): List<Tag> {
        val chosen = selectedTags.mapTo(mutableSetOf()) { it.value }
        val present = decks
            .filter { deck -> deck.tags.containsAll(chosen) }
            .flatMapTo(mutableSetOf()) { it.tags }
            .filterNot { it in chosen || ReservedTags.isReserved(it) }
            .toSet()
        val ranked = topics.items.filter { it.value in present }
        val rankedLabels = ranked.mapTo(mutableSetOf()) { it.value }
        return selectedTags + ranked + present.filterNot { it in rankedLabels }.sorted().map(::Tag)
    }

    /**
     * Global browse minus whatever the follow strip is already showing.
     *
     * The two strips load independently and neither knew about the other, so a followed author's
     * deck was drawn twice on the same screen — once under "From people you follow" and again
     * under "Discover decks". The follow strip wins because it is the more specific claim.
     *
     * Derived rather than filtered at write time: the strips settle in either order, so the
     * exclusion has to be recomputed on every emission rather than applied once.
     */
    val browseExcludingFollowed: SectionState<DiscoverDeck>
        get() {
            if (following.items.isEmpty()) return browse
            val shown = following.items.mapTo(mutableSetOf()) { it.authorPubky to it.id }
            return browse.copy(items = browse.items.filterNot { (it.authorPubky to it.id) in shown })
        }

    /**
     * True when browse found matches and the follow strip is already showing every one of them.
     *
     * The distinction the empty state turns on. "No decks tagged X yet" is a claim about the
     * world, and it is false whenever the only matches happen to be decks you follow — selecting
     * a tag with one such deck rendered that sentence directly above the deck it denied. So the
     * browse section is dropped whole in this case rather than drawn empty: there is nothing to
     * say, and the deck is on screen already.
     */
    val browseFullyCoveredByFollowed: Boolean
        get() = browse.items.isNotEmpty() && browseExcludingFollowed.items.isEmpty()
}

/** Someone worth following, with the state of the follow pill beside them. */
data class DiscoverPerson(
    val identity: PubkyIdentity,
    val isFollowing: Boolean = false,
    val isFollowPending: Boolean = false,
)

/**
 * One independently-loading strip. Strips never gate each other.
 *
 * [hasMore] and [isLoadingMore] are separate from [isLoading] because they draw different things: a
 * first load is a strip that is not there yet, a page load is a footer under a strip the reader is
 * already looking at. Collapsing them made the whole grid disappear on every "load more".
 */
data class SectionState<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = false,
    val error: ErrorReason? = null,
    /** Where the next page resumes. Meaningless to anything but the repository that issued it. */
    val cursor: Int = DeckPage.START,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** A failed *page*, shown under the items rather than instead of them. */
    val pageError: ErrorReason? = null,
) {
    /** Settled with nothing to show — the only case that draws an empty placeholder. */
    val isEmpty: Boolean get() = items.isEmpty() && !isLoading && error == null

    /** True only when another page is worth asking for right now. */
    /**
     * True only when another page is worth asking for right now. A standing [pageError] blocks it:
     * the sentinel is still on screen, so without this a failed page retries on every recomposition
     * — a spin against a host that is not answering.
     */
    val canLoadMore: Boolean
        get() = hasMore && !isLoading && !isLoadingMore && error == null && pageError == null

    fun loading(): SectionState<T> = copy(isLoading = true, error = null, pageError = null)

    /** First page: replaces the items and resets the cursor. */
    fun loaded(items: List<T>, cursor: Int = DeckPage.START, hasMore: Boolean = false) =
        SectionState(items = items, cursor = cursor, hasMore = hasMore)

    /** A later page: appends. */
    fun appended(more: List<T>, cursor: Int, hasMore: Boolean) = copy(
        items = items + more,
        cursor = cursor,
        hasMore = hasMore,
        isLoadingMore = false,
        error = null,
        pageError = null,
    )

    /** The strip itself could not load: [items] is empty and [error] is what to show instead. */
    fun failed(reason: ErrorReason): SectionState<T> =
        copy(isLoading = false, isLoadingMore = false, error = reason)

    /**
     * A *page* could not load. Keeps the items already on screen and stops the footer spinning;
     * [hasMore] stays true, so the footer is still a retry rather than the end of the list.
     */
    fun pageFailed(reason: ErrorReason): SectionState<T> =
        copy(isLoadingMore = false, pageError = reason)
}

data class DiscoverDeck(
    val id: String,
    val authorPubky: String,
    val title: String,
    val cardCount: Int,
    val coverEmoji: String,
    /** The deck's cover art, when it has one. Renders over [coverEmoji]; null falls back to it. */
    val coverImage: MediaRef.Image? = null,
    val author: PubkyIdentity,
    val tags: List<String>,
)

sealed interface DiscoverEffect {
    /**
     * Open search. Discover shows what the network happens to be publishing; search is how
     * someone with a name, a pubky or a shared link finds the one thing they came for — including
     * an account too new for any index, which is why pasting a pubky has to stay reachable.
     */
    data object OpenSearch : DiscoverEffect
    data class OpenProfile(val pubky: String) : DiscoverEffect
    data class OpenDeck(val authorPubky: String, val deckId: String) : DiscoverEffect

    /** A follow that failed — the pill has already reverted, so this only explains why. */
    data class ShowFollowError(val reason: ErrorReason) : DiscoverEffect

    /** A guest reached for something that writes. [reason] is what the prompt is about. */
    data class RequireSignIn(val reason: SignInReason) : DiscoverEffect
}
