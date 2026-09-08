package com.github.jvsena42.loopky.presentation.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.DeckCounts
import com.github.jvsena42.loopky.domain.model.DeckMastery
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.presentation.share.DeckSharePrompt
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

@OptIn(ExperimentalEncodingApi::class)
@Suppress("LongParameterList", "TooManyFunctions")
class DeckDetailViewModel(
    private val deckId: String,
    private val authorPubky: String? = null,
    private val deckRepository: DeckRepository,
    private val cardRepository: CardRepository,
    private val identityRepository: IdentityRepository,
    private val srsRepository: SrsRepository,
    private val mediaRepository: MediaRepository,
    private val tagRepository: TagRepository,
    private val discoveryRepository: DiscoveryRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow<DeckDetailUiState>(DeckDetailUiState.Loading)
    val state: StateFlow<DeckDetailUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DeckDetailEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DeckDetailEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    /** Re-entrancy guard, so double-tapping the follow pill cannot race two writes. */
    private var followJob: Job? = null

    /**
     * Where to go once the share prompt resolves, when the action that raised it was a clone.
     * Null for a follow, which leaves the user on this screen.
     */
    private var pendingCloneDeckId: String? = null

    init {
        load()
        // Studying runs on its own full-screen destination while this screen stays composed
        // behind it, so without this the due count keeps the value it had before the session.
        viewModelScope.launch {
            srsRepository.changes
                .filter { it == deckId }
                .collect { refreshSrsCounters() }
        }
    }

    fun onRefresh() = load()

    /** [silent] keeps existing content on screen while a background refresh runs. */
    private fun load(silent: Boolean = false) {
        // Cancel rather than bail out: a review that lands mid-load must not be dropped.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: deckId=$deckId (silent=$silent)")
            if (!silent) _state.update { DeckDetailUiState.Loading }

            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()

            var deck = deckRepository.getLocal(deckId)
            if (deck == null && authorPubky != null) {
                Log.d(TAG, "load: cache miss, fetching remote author=$authorPubky")
                deck = deckRepository.fetchRemote(authorPubky, deckId)
                    .onFailure { err ->
                        Log.e(TAG, "load: remote fetch FAILED — ${err::class.simpleName}: ${err.message}", err)
                    }
                    .getOrNull()
            }
            if (deck == null) {
                _state.update { DeckDetailUiState.Error(ErrorReason.NotFound, canRetry = false) }
                return@launch
            }

            // Must be a fetch, not a cache read: nothing has loaded this deck's cards yet on a
            // cold launch, and for a deck you don't own nothing ever will.
            // fetchByDeck already returns study order; sorting it again here cost a second pass
            // over every card in the deck, on the main thread.
            runSuspendCatching { cardRepository.fetchByDeck(deck).getOrThrow() }
                .onSuccess { cards ->
                    // Null, not zero, when the read fails: rendering "Due 0 · Mastered 0%" showed a
                    // fully-mature deck as untouched and caught up, with no error anywhere (#101).
                    val counts = runSuspendCatching { srsRepository.countsForDeck(deckId) }
                        .onFailure { Log.e(TAG, "load: counts FAILED — ${it.message}", it) }
                        .getOrNull()
                    val mastered = masteredLabel(
                        runSuspendCatching { srsRepository.mastery(deckId, cards.map { it.id }) }
                            .onFailure { Log.e(TAG, "load: mastery FAILED — ${it.message}", it) }
                            .getOrNull(),
                    )
                    val isFollowing = runSuspendCatching { deckRepository.isFollowingDeck(deckId) }
                        .getOrDefault(false)
                    _state.update {
                        deck.toContent(cards, session?.identity, counts, mastered, isFollowing)
                    }
                    Log.d(TAG, "load: cards=${cards.size} counts=$counts mastered=$mastered")
                    loadCoverBlob(deck.coverImageRef, deck.authorPubky)
                    loadAuthorProfile(deck.authorPubky)
                    loadClonedFrom(deck)
                    loadCounts(deck)
                    // Opening a followed deck is what "seen" means, so the library stops flagging
                    // it as changed. Last, and best-effort: it is cosmetic.
                    if (isFollowing) runSuspendCatching { deckRepository.markSeen(deck) }
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.update { DeckDetailUiState.Error(err.toErrorReason()) }
                }
        }
    }

    fun onBackClick() {
        viewModelScope.launch { _effects.emit(DeckDetailEffect.NavigateBack) }
    }

    fun onShareClick() {
        viewModelScope.launch {
            val deck = deckRepository.getLocal(deckId) ?: return@launch
            _effects.emit(DeckDetailEffect.Share(title = deck.title, uri = deck.pubkyUri.value))
        }
    }

    fun onStudyClick() {
        // Keeping the deck is what earns review state, so browsing someone else's deck is not
        // enough to *study* it. What it is enough for is a look through the cards: a preview
        // grades nothing and writes nothing, so it needs neither the deck nor an account — which
        // is the whole point, since it is the only thing a signed-out visitor can do with a deck
        // beyond reading the rows. The UI picks the label; this decides which one it gets.
        val current = _state.value as? DeckDetailUiState.Content ?: return
        val effect = when {
            current.isOwned || current.isFollowing -> DeckDetailEffect.NavigateStudy
            current.canPreview -> DeckDetailEffect.NavigateStudyPreview
            else -> return
        }
        viewModelScope.launch { _effects.emit(effect) }
    }

    /**
     * Edit, and the one place a copy is offered (#254).
     *
     * A followed deck is the author's: it is read-only, and it keeps receiving their changes. So
     * "edit this" is the moment — and the only moment — that owning a copy is the answer, which is
     * why Clone is no longer a second pill competing with Follow. Nothing is copied here; the
     * confirm names what a copy costs and collects the title it needs.
     */
    fun onEditClick() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        if (current.isOwned) {
            viewModelScope.launch { _effects.emit(DeckDetailEffect.NavigateEditDeck(deckId)) }
            return
        }
        // Kept for a screen that offers this on a deck the reader has not followed: the copy is
        // written under their own pubky, and a guest has none. A greyed-out button explains
        // nothing, so the prompt is raised where the action is reached.
        if (!current.isSignedIn) {
            _state.update { current.copy(signInPrompt = SignInReason.CloneDeck) }
            return
        }
        _state.update { current.copy(showCloneConfirm = true) }
    }

    fun onDeleteDeck() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showDeleteConfirm = true) }
    }

    fun onDismissDelete() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showDeleteConfirm = false) }
    }

    fun onConfirmDelete() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showDeleteConfirm = false, isDeleting = true) }
        viewModelScope.launch {
            Log.d(TAG, "onConfirmDelete: deckId=$deckId")
            deckRepository.delete(deckId)
                .onSuccess { _effects.emit(DeckDetailEffect.Deleted) }
                .onFailure { err ->
                    Log.e(TAG, "onConfirmDelete: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.update { DeckDetailUiState.Error(err.toErrorReason()) }
                }
        }
    }

    /**
     * Subscribe to / unsubscribe from someone else's deck (#33).
     *
     * Optimistic, like the author-follow toggle on friend profiles: the pill flips immediately and
     * reverts if the write fails, because waiting on a homeserver round trip to acknowledge a tap
     * reads as a dead button.
     */
    fun onToggleFollow() {
        if (followJob?.isActive == true) return
        val current = _state.value as? DeckDetailUiState.Content ?: return
        if (current.isOwned) return
        // The subscription is a record written under the reader's own pubky, so there is nothing
        // for a guest to write it to. Asked here rather than only where the pill is drawn: the
        // pill stays live on purpose — reaching for it is exactly the moment the account is worth
        // explaining, and a greyed-out button explains nothing.
        if (!current.isSignedIn) {
            _state.update { current.copy(signInPrompt = SignInReason.FollowDeck) }
            return
        }

        followJob = viewModelScope.launch {
            val wasFollowing = current.isFollowing
            // `canPreview` travels with the flip, or the optimistic update leaves it stale: a deck
            // you have just kept still offered "Try these cards" — a session that grades nothing —
            // over the study it now has. Restored, not recomputed, so a revert is exact.
            val wasCanPreview = current.canPreview
            _state.update { s ->
                (s as? DeckDetailUiState.Content)?.copy(
                    isFollowing = !wasFollowing,
                    isFollowPending = true,
                    canPreview = if (wasFollowing) wasCanPreview else false,
                ) ?: s
            }

            val deck = deckRepository.getLocal(deckId)
            if (deck == null) {
                _state.update { s ->
                    (s as? DeckDetailUiState.Content)?.copy(
                        isFollowing = wasFollowing,
                        isFollowPending = false,
                        canPreview = wasCanPreview,
                    ) ?: s
                }
                return@launch
            }

            val result = if (wasFollowing) {
                deckRepository.unfollowDeck(deck.authorPubky, deck.id)
            } else {
                deckRepository.followDeck(deck)
            }
            result
                .onSuccess {
                    _state.update { s ->
                        (s as? DeckDetailUiState.Content)?.copy(isFollowPending = false) ?: s
                    }
                    // Only a follow is worth announcing. Unfollowing is not news, and posting it
                    // would tell someone's followers what they stopped reading.
                    if (!wasFollowing) {
                        offerShare(deck, DeckAnnouncement.Kind.Followed, current.author.pubky)
                    }
                }
                .onFailure { err ->
                    Log.e(TAG, "onToggleFollow: FAILED — ${err.message}", err)
                    _state.update { s ->
                        (s as? DeckDetailUiState.Content)?.copy(
                            isFollowing = wasFollowing,
                            isFollowPending = false,
                            canPreview = wasCanPreview,
                            errorReason = err.toErrorReason(),
                        ) ?: s
                    }
                }
        }
    }

    /** The visitor read the prompt and stayed. Nothing was written either way. */
    fun onDismissSignInPrompt() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(signInPrompt = null) }
    }

    fun onDismissClone() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showCloneConfirm = false) }
    }

    /**
     * [title] is the copy's own name, collected in the confirm and mandatory — see
     * [DeckRepository.clone]. Held by the screen rather than in this state: a text field that
     * round-trips every keystroke through a ViewModel drops characters on iOS.
     */
    fun onConfirmClone(title: String) {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        // Guarded here as well as in the dialog, which disables its confirm: the rule is what makes
        // the copy findable next to the deck it forked, so it cannot live only in a button's state.
        if (title.isBlank() || current.isSourceName(title)) return
        _state.update { current.copy(showCloneConfirm = false, isCloning = true) }
        viewModelScope.launch {
            val source = deckRepository.getLocal(deckId)
            if (source == null) {
                _state.update { s ->
                    (s as? DeckDetailUiState.Content)?.copy(isCloning = false) ?: s
                }
                return@launch
            }
            deckRepository.clone(source, title)
                .onSuccess { clone ->
                    Log.d(TAG, "onConfirmClone: $deckId -> ${clone.id}")
                    // The copy is what gets announced — it is the deck the user now owns — with
                    // the source author credited in the text, matching the clone's provenance.
                    pendingCloneDeckId = clone.id
                    // Cleared here rather than inside offerShare, which returns early — and used
                    // to return without clearing it — when the user has announcements switched
                    // off. The copy is written by this point either way, so leaving the spinner up
                    // for the one setting stranded the screen on "Copying deck…" forever.
                    _state.update { s ->
                        (s as? DeckDetailUiState.Content)?.copy(isCloning = false) ?: s
                    }
                    val offered = offerShare(
                        clone,
                        DeckAnnouncement.Kind.Cloned,
                        current.author.pubky,
                    )
                    // Navigate to the copy: it is what the user now owns, and the source screen
                    // would otherwise sit there looking unchanged. Held back while the prompt is
                    // up, or the dialog would be torn down before it could be answered.
                    if (!offered) leaveAfterClone()
                }
                .onFailure { err ->
                    Log.e(TAG, "onConfirmClone: FAILED — ${err.message}", err)
                    _state.update { s ->
                        (s as? DeckDetailUiState.Content)?.copy(
                            isCloning = false,
                            errorReason = err.toErrorReason(),
                        ) ?: s
                    }
                }
        }
    }

    /**
     * Offer to announce [deck] on Pubky (#39), returning whether the prompt was actually raised.
     *
     * False when the user has turned the offer off in Settings, in which case nothing is asked and
     * nothing is written — that is what "off" means here, not "post silently".
     */
    private suspend fun offerShare(
        deck: Deck,
        kind: DeckAnnouncement.Kind,
        sourceAuthorPubky: String?,
    ): Boolean {
        if (!appPreferences.shareOnPubky.first()) return false
        val announcement = DeckAnnouncement.of(deck, kind, sourceAuthorPubky)
        _state.update { s ->
            (s as? DeckDetailUiState.Content)?.copy(sharePrompt = DeckSharePrompt(announcement)) ?: s
        }
        return true
    }

    /** Post the announcement, then resolve the prompt regardless — the action itself stands. */
    fun onShareConfirm() {
        val prompt = sharePromptIfIdle() ?: return
        viewModelScope.launch {
            _state.update { s ->
                (s as? DeckDetailUiState.Content)?.copy(sharePrompt = prompt.copy(isPosting = true)) ?: s
            }
            discoveryRepository.announceDeck(prompt.announcement)
                .onSuccess { _effects.emit(DeckDetailEffect.Shared) }
                .onFailure { err ->
                    Log.e(TAG, "onShareConfirm: FAILED — ${err.message}", err)
                    _effects.emit(DeckDetailEffect.ShareFailed)
                }
            dismissSharePrompt()
        }
    }

    fun onShareDismiss() {
        sharePromptIfIdle() ?: return
        viewModelScope.launch { dismissSharePrompt() }
    }

    /** Declines *and* turns the offer off, so the prompt and the Settings switch stay one setting. */
    fun onShareNeverAsk() {
        sharePromptIfIdle() ?: return
        viewModelScope.launch {
            appPreferences.setShareOnPubky(false)
            dismissSharePrompt()
        }
    }

    private fun sharePromptIfIdle(): DeckSharePrompt? =
        (_state.value as? DeckDetailUiState.Content)?.sharePrompt?.takeIf { !it.isPosting }

    private suspend fun dismissSharePrompt() {
        _state.update { s -> (s as? DeckDetailUiState.Content)?.copy(sharePrompt = null) ?: s }
        leaveAfterClone()
    }

    /** No-op after a follow, which never leaves this screen. */
    private suspend fun leaveAfterClone() {
        val cloneId = pendingCloneDeckId ?: return
        pendingCloneDeckId = null
        _effects.emit(DeckDetailEffect.Cloned(cloneId))
    }

    fun onDismissError() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(errorReason = null) }
    }

    /**
     * Resolves "Cloned from @someone" for a deck that carries clone provenance, so credit is
     * visible on the copy and not only in the manifest.
     */
    private suspend fun loadClonedFrom(deck: Deck) {
        val uri = deck.source?.takeIf { it.kind == DeckSource.Kind.Clone }?.uri ?: return
        val ref = parseDeckManifestUri(uri) ?: return
        val profile = identityRepository.fetchProfile(ref).getOrNull()
            ?: PubkyIdentity(ref, displayName = null, avatarUrl = null, bio = null)
        _state.update { s ->
            (s as? DeckDetailUiState.Content)?.copy(clonedFrom = profile) ?: s
        }
    }

    /**
     * Follower and clone counts, from the indexer's distinct-tagger count for the reserved labels.
     * Approximate by nature (indexer lag, spam) — fine to display, never to gate on — and zero
     * whenever the indexer is unreachable, so this never blocks the screen.
     */
    private suspend fun loadCounts(deck: Deck) {
        val counts = runSuspendCatching { tagRepository.taggerCounts(deck.pubkyUri) }
            .getOrDefault(emptyMap())
        if (counts.isEmpty()) return
        _state.update { s ->
            (s as? DeckDetailUiState.Content)?.copy(
                followerCount = counts[ReservedTags.FOLLOWED] ?: 0,
                clonedCount = counts[ReservedTags.CLONED] ?: 0,
            ) ?: s
        }
    }

    /**
     * The author pubky out of `pubky://{author}/pub/loopky/decks/{id}/manifest.json`.
     *
     * Parsed here rather than through `PubkyUris.parseDeckManifest`, which is `internal` to the data
     * layer — presentation only needs the owner segment.
     */
    private fun parseDeckManifestUri(uri: String): String? {
        if (!uri.startsWith(PUBKY_SCHEME)) return null
        return uri.removePrefix(PUBKY_SCHEME).substringBefore('/', "").ifEmpty { null }
    }

    /**
     * How mature the deck is, as a label.
     *
     * The share itself is the repository's ([SrsRepository.masteryShare]) — it is the only thing
     * holding both the review states and the user's own maturity threshold. This turns it into
     * words, and the edges are the point:
     *
     * - `"—"` for an empty deck, and for a share of `null`, which means the read failed. Zero would
     *   claim a fully-mature deck was untouched.
     * - `"<1%"` for any real progress that rounds away. On a 1669-card deck five cards at 7 days is
     *   0.1%, and "started" has to look different from "nothing" (#101 §2).
     * - capped at 99% until every card is genuinely mature, so rounding cannot promise a finished
     *   deck.
     */
    private fun masteredLabel(mastery: DeckMastery?): String {
        if (mastery == null) return "—"
        if (mastery.isComplete) return "$PERCENT%"
        val percent = (mastery.share * PERCENT).roundToInt()
        return when {
            mastery.share <= 0f -> "0%"
            mastery.share < MIN_VISIBLE_SHARE -> "<1%"
            // Rounding must never promise a finished deck that isComplete just denied.
            percent >= PERCENT -> "${PERCENT - 1}%"
            else -> "$percent%"
        }
    }

    /**
     * Recompute just the two numbers a review can move — due count and mastered share — without
     * touching the network.
     *
     * This runs once per graded card, because studying happens on its own destination while this
     * screen stays composed behind it. It used to be a full [load], which meant every single grade
     * re-synced the deck manifest, rebuilt the whole card list, and re-fetched the author's profile,
     * the cover blob and the Nexus tagger counts — none of which a review can change (#102).
     */
    private suspend fun refreshSrsCounters() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        val cardIds = current.cardPreviews.map { it.id }
        // Deliberately not recounted here: the due rule is the repository's, and a second copy of
        // it in the presentation layer is a second thing to keep in step.
        val counts = runSuspendCatching { srsRepository.dueCountsCached()[deckId] }
            .onFailure { Log.e(TAG, "refreshSrsCounters: counts FAILED — ${it.message}", it) }
            .getOrNull()
        val mastered = runSuspendCatching { srsRepository.mastery(deckId, cardIds) }
            .onFailure { Log.e(TAG, "refreshSrsCounters: mastery FAILED — ${it.message}", it) }
            .getOrNull()
        _state.update { s ->
            (s as? DeckDetailUiState.Content)?.copy(
                // A missing entry means the cache cannot speak for this deck — which is not the
                // same as the load-time failure that renders "—". Keep the last real numbers.
                dueLabel = counts?.due?.toString() ?: s.dueLabel,
                newCards = counts?.new ?: s.newCards,
                canStudy = counts?.let { it.total > 0 } ?: s.canStudy,
                masteredPercent = mastered?.let { masteredLabel(it) } ?: s.masteredPercent,
            ) ?: s
        }
    }

    /**
     * Fetches a homeserver blob cover and folds its Base64 bytes into the current [Content] so the
     * UI can render the real image. Remote (URL) covers need no fetch — they are already carried by
     * [DeckDetailUiState.Content.coverImageUrl]. No-ops on null/remote refs or while not in Content.
     */
    private suspend fun loadCoverBlob(ref: MediaRef.Image?, authorPubky: String) {
        if (ref == null || ref.isRemote) return
        val bytes = mediaRepository.get(authorPubky, deckId, ref)
            .onFailure { Log.e(TAG, "loadCoverBlob: FAILED — ${it.message}", it) }
            .getOrNull() ?: return
        val encoded = Base64.encode(bytes)
        _state.update { current ->
            (current as? DeckDetailUiState.Content)?.copy(coverImageBase64 = encoded) ?: current
        }
    }

    /**
     * Fetches the author's pubky.app profile and folds it into the current [Content], so the author
     * row shows the same name and picture the author's own profile screen does. Keeps whatever the
     * session already gave us when the author has published no profile.
     */
    private suspend fun loadAuthorProfile(authorPubky: String) {
        val profile = identityRepository.fetchProfile(authorPubky).getOrNull() ?: return
        _state.update { current ->
            (current as? DeckDetailUiState.Content)?.let {
                it.copy(
                    author = it.author.copy(
                        displayName = profile.displayName ?: it.author.displayName,
                        avatarUrl = profile.avatarUrl ?: it.author.avatarUrl,
                    ),
                )
            } ?: current
        }
    }

    private fun Deck.toContent(
        cards: List<Card>,
        myIdentity: PubkyIdentity?,
        counts: DeckCounts?,
        mastered: String,
        isFollowing: Boolean,
    ): DeckDetailUiState.Content {
        val isOwned = authorPubky == myIdentity?.pubky
        return DeckDetailUiState.Content(
            isSignedIn = myIdentity != null,
            isFollowing = isFollowing,
            deckId = id,
            title = title,
            description = description,
            coverEmoji = coverEmoji ?: title.firstOrNull()?.uppercaseChar()?.toString() ?: "📚",
            coverImageUrl = coverImageRef?.url,
            // Your own decks can name you straight away from the session; for anyone else the
            // pubky stands in until loadAuthorProfile lands.
            author = myIdentity?.takeIf { isOwned }
                ?: PubkyIdentity(authorPubky, displayName = null, avatarUrl = null, bio = null),
            isOwned = isOwned,
            isIncomplete = incomplete,
            tags = tags.map { it.value },
            totalCards = cardCount,
            dueLabel = counts?.due?.toString() ?: UNKNOWN_STAT,
            newCards = counts?.new ?: 0,
            // Unknown counts must not disable Study — the queue is the repository's to build, and
            // refusing to open it because a count failed strands the user with no way in.
            canStudy = counts == null || counts.total > 0,
            // A deck nobody has kept can still be sampled, as long as it has cards to sample.
            // Deliberately not limited to guests: a signed-in reader looking at a stranger's deck
            // is in exactly the same position — no review state, nothing to be due — and giving
            // them a way to try it before following is the point of the button.
            canPreview = !isOwned && !isFollowing && cards.isNotEmpty(),
            masteredPercent = mastered,
            cardPreviews = cards.map { it.toPreview() },
        )
    }

    private fun Card.toPreview(): CardPreviewModel = CardPreviewModel(
        id = id,
        frontText = front.text ?: "",
        backText = back.text ?: "",
        frontImageRef = front.imageRef,
    )

    companion object {
        private const val TAG = "Loopky/DeckDetailVM"

        private const val PERCENT = 100

        /** Below this, a real share rounds to zero and has to say "<1%" instead. */
        private const val MIN_VISIBLE_SHARE = 0.01f

        /** What a stat reads when it could not be read at all — never "0". */
        private const val UNKNOWN_STAT = "—"
        private const val PUBKY_SCHEME = "pubky://"
    }
}

sealed interface DeckDetailUiState {
    data object Loading : DeckDetailUiState
    data class Content(
        val deckId: String,
        val title: String,
        val description: String?,
        val coverEmoji: String,
        val coverImageUrl: String? = null,
        val coverImageBase64: String? = null,
        val author: PubkyIdentity,
        /** Ownership is a separate concern from identity — the author row shows both. */
        val isOwned: Boolean,
        /**
         * Whether anyone is signed in. Deck detail is fully readable without an account — the
         * manifest and cards are public records — so this gates only the actions that write:
         * Follow, and the copy behind Edit. Always true alongside [isOwned], which needs a session
         * to establish.
         */
        val isSignedIn: Boolean = true,
        /**
         * The deck was claimed by a publish that never finished, so some of its cards are missing.
         * Surfaced rather than hidden: the count comes from the manifest, so the deck would
         * otherwise look complete while silently holding fewer cards than it claims.
         */
        val isIncomplete: Boolean = false,
        val tags: List<String>,
        /**
         * You hold a subscription to this deck: you receive the author's updates and it is
         * read-only. Always false for a deck you own — you cannot follow yourself.
         */
        val isFollowing: Boolean = false,
        /** A follow/unfollow write is in flight; the pill is already showing its new state. */
        val isFollowPending: Boolean = false,
        val showCloneConfirm: Boolean = false,
        val isCloning: Boolean = false,
        /** The author this deck was cloned from, when it carries clone provenance. */
        val clonedFrom: PubkyIdentity? = null,
        /**
         * Distinct taggers of the reserved labels, per the indexer. Approximate by nature (indexer
         * lag, spam) and zero while the indexer is unreachable — display only, never gate on them.
         */
        val followerCount: Int = 0,
        val clonedCount: Int = 0,
        val totalCards: Int,
        /**
         * Cards waiting for review, already formatted — "—" when the read failed.
         *
         * A String rather than an `Int?` for the same reason [masteredPercent] is one: the stats
         * row shows three pre-formatted values, and a nullable Int would push a `KotlinInt?` across
         * the Swift bridge for a number that is only ever displayed.
         */
        val dueLabel: String,
        /** Cards never graded. Separate from due — nothing about an unseen card is late (#101 §7). */
        val newCards: Int = 0,
        /** Whether Study can do anything. False for a deck with no reviews *and* no unseen cards. */
        val canStudy: Boolean = true,
        /**
         * This deck can be *tried* without being kept: flip through its cards, grading nothing.
         * True only when [isOwned] and [isFollowing] are both false — otherwise there is real
         * study to be had and a preview would be the worse of two buttons.
         */
        val canPreview: Boolean = false,
        /**
         * A guest reached for an action that writes. Holds the prompt over the loaded deck rather than
         * replacing it: the deck is still perfectly readable, and dismissing must leave them
         * exactly where they were.
         */
        val signInPrompt: SignInReason? = null,
        val masteredPercent: String,
        val cardPreviews: List<CardPreviewModel>,
        val showDeleteConfirm: Boolean = false,
        val isDeleting: Boolean = false,
        /** "Share this on Pubky?" after a follow or a clone, unless the user opted out (#39). */
        val sharePrompt: DeckSharePrompt? = null,
        /** A recoverable failure worth showing without tearing down the loaded deck. */
        val errorReason: ErrorReason? = null,
    ) : DeckDetailUiState {
        /**
         * The pencil is offered. Wider than [isOwned]: a followed deck carries it too, and there it
         * offers a copy rather than the editor (#254).
         */
        val canEdit: Boolean get() = isOwned || isFollowing

        /**
         * Whether [title] is just this deck's own name again — the one thing a copy's mandatory
         * name may not be, since the point of asking is that the copy lands in a library beside
         * the deck it forked. Case- and space-insensitive: "spanish basics " reads as the same
         * row as "Spanish Basics", so accepting it would defeat the rule while looking like it
         * passed. Lives on the state so both platforms' dialogs check the same rule live, rather
         * than each reimplementing the comparison.
         */
        fun isSourceName(candidate: String): Boolean =
            candidate.trim().equals(title.trim(), ignoreCase = true)
    }
    data class Error(
        val reason: ErrorReason,
        /** False when retrying cannot possibly succeed (e.g. the deck no longer exists). */
        val canRetry: Boolean = true,
    ) : DeckDetailUiState
}

data class CardPreviewModel(
    val id: String,
    val frontText: String,
    val backText: String,
    /**
     * The front's picture, for the rows whose front is a picture and nothing else. An Anki `Basic`
     * note routinely holds only an `<img>` in its first field, and a text-only row then names the
     * card by its answer alone (#96).
     */
    val frontImageRef: MediaRef.Image? = null,
)

sealed interface DeckDetailEffect {
    data object NavigateBack : DeckDetailEffect
    data class NavigateEditDeck(val deckId: String) : DeckDetailEffect
    data object NavigateStudy : DeckDetailEffect

    /** Flip through the deck's cards without grading them — see [DeckDetailUiState.Content.canPreview]. */
    data object NavigateStudyPreview : DeckDetailEffect
    data class Share(val title: String, val uri: String) : DeckDetailEffect
    data object Deleted : DeckDetailEffect

    /** The clone is what the user now owns, so the screen moves to it rather than staying put. */
    data class Cloned(val deckId: String) : DeckDetailEffect

    /** The announcement post went out, or didn't. Cosmetic either way — the action stands. */
    data object Shared : DeckDetailEffect
    data object ShareFailed : DeckDetailEffect
}
