package com.github.jvsena42.loopky.presentation.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.DeckLimits
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.FormError
import com.github.jvsena42.loopky.domain.model.LanguageTags
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.SpeechLanguages
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.domain.model.remoteImageRef
import com.github.jvsena42.loopky.presentation.share.DeckSharePrompt
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.generateId
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The deck editor: metadata, plus a **paged** view of the deck's cards.
 *
 * Two rules follow from Anki-sized decks (#52), and the rest of this class is their consequence:
 *
 * 1. **The card list is a window, never the deck.** Cards arrive one chunk at a time as the user
 *    scrolls; a 20k-card deck must not become 20,000 `EditableCardModel`s when the screen opens.
 * 2. **Saving an existing deck writes the manifest only.** Rebuilding the deck's cards from a
 *    window would delete everything not yet paged in, so card mutations do not wait for Save:
 *    they go straight through [DeckRepository.upsertCard] and [DeckRepository.moveCard].
 *
 * A deck that does not exist yet ([deckId] null) is the exception on both counts — it has nowhere
 * to write incrementally, and it is small by construction.
 */
// Seven collaborators because the editor writes a deck end to end: manifest, cards, cover upload,
// the author it stamps, and — since #39 — the announcement it offers on a create.
@Suppress("TooManyFunctions", "LongParameterList")
@OptIn(ExperimentalEncodingApi::class)
class DeckEditorViewModel(
    private val deckId: String?,
    private val deckRepository: DeckRepository,
    private val cardRepository: CardRepository,
    private val identityRepository: IdentityRepository,
    private val mediaRepository: MediaRepository,
    private val discoveryRepository: DiscoveryRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(DeckEditorUiState())
    val state: StateFlow<DeckEditorUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DeckEditorEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DeckEditorEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var saveJob: Job? = null

    /**
     * The deck as last read. Saving rewrites the whole manifest, so the fields the editor does not
     * expose (cover, Listen/Speak, provenance) — and the chunk table, which only the card writes
     * move — must be carried forward from here or they are destroyed on the homeserver.
     */
    private var loadedDeck: Deck? = null

    /** The deck's chunk records in study order: this editor's page list. */
    private var pageOrder: List<Int> = emptyList()

    /** How many of [pageOrder] have been read in. Also the index of the next page to fetch. */
    private var pagesLoaded = 0

    /**
     * Serializes the writes behind [onMoveCard]. Each move resolves its destination from the
     * manifest's chunk counts, so two in flight would both plan against the pre-move table and the
     * second would land in the wrong place.
     */
    private val moveLock = Mutex()

    /** The id the last successful save wrote — the destination once the share prompt resolves. */
    private var savedDeckId: String? = null

    init {
        if (deckId != null) loadExisting()
    }

    private fun loadExisting() {
        loadJob = viewModelScope.launch {
            Log.d(TAG, "loadExisting: deckId=$deckId")
            val deck = deckRepository.getLocal(deckId!!) ?: return@launch
            loadedDeck = deck
            _state.update {
                DeckEditorUiState(
                    isNew = false,
                    coverEmoji = deck.coverEmoji ?: deck.title.firstOrNull()?.toString() ?: "",
                    // The cover this screen is about to edit. A remote cover is its URL; a
                    // homeserver blob has none, so loadCoverBlob fetches its bytes below. Without
                    // both, the one screen that can *replace* a cover is the one screen that
                    // cannot show you the cover you are replacing (#166).
                    coverImageUrl = deck.coverImageRef?.url,
                    title = deck.title,
                    description = deck.description ?: "",
                    tags = deck.tags.map { tag -> tag.value },
                    totalCards = deck.cardCount,
                    isLoadingCards = true,
                    // Folded through speechReady like the study screen does: a deck published
                    // before the pair existed carries the opt-ins but offers neither feature, so
                    // showing them on would misreport what the deck actually does today.
                    listenEnabled = deck.listenEnabled && deck.speechReady,
                    speakEnabled = deck.speakEnabled && deck.speechReady,
                    // Not folded through speechReady: typing works without a declared pair, so
                    // what the deck says is what the deck does.
                    typeEnabled = deck.typeEnabled,
                    reverseEnabled = deck.reverseEnabled,
                    frontLang = deck.frontLang,
                    backLang = deck.backLang,
                )
            }
            pageOrder = deck.chunks.sortedBy { it.n }.map { it.n }
            pagesLoaded = 0
            // Off loadJob deliberately: a child would keep it active, and onLoadMoreCards
            // refuses to page while it is.
            viewModelScope.launch { loadCoverBlob(deck) }
            if (pageOrder.isEmpty()) loadWholeDeck(deck) else appendNextPage(deck)
        }
    }

    /**
     * Folds a homeserver blob cover's Base64 bytes into the state. Remote (URL) covers need no fetch,
     * and a cover picked since the load wins, so this drops its result rather than overwriting one.
     */
    private suspend fun loadCoverBlob(deck: Deck) {
        val ref = deck.coverImageRef?.takeIf { !it.isRemote } ?: return
        val bytes = mediaRepository.get(deck.authorPubky, deck.id, ref)
            .onFailure { Log.e(TAG, "loadCoverBlob: FAILED — ${it.message}", it) }
            .getOrNull() ?: return
        val encoded = Base64.encode(bytes)
        _state.update { s ->
            if (s.coverImageUrl != null || s.coverPendingBytes != null) s else s.copy(coverImageBase64 = encoded)
        }
    }

    /**
     * Read every card in one go, for a deck whose manifest carries no chunk table. Only decks
     * published before the chunked layout look like this, and they are small — there are no page
     * boundaries to page along, so this is the old whole-deck read kept for exactly those decks.
     */
    private suspend fun loadWholeDeck(deck: Deck) {
        var reason: ErrorReason? = null
        val cards = cardRepository.fetchByDeck(deck)
            .onFailure { err ->
                Log.e(TAG, "loadWholeDeck: FAILED — ${err.message}", err)
                reason = err.toErrorReason()
            }
            .getOrNull()
            ?.inStudyOrder()
        _state.update { s ->
            s.copy(
                cards = cards.orEmpty().map { it.toEditable() },
                totalCards = maxOf(deck.cardCount, cards?.size ?: 0),
                isLoadingCards = false,
                hasMoreCards = false,
                error = reason?.let { DeckEditorError(DeckEditorOp.LoadCards, it) } ?: s.error,
            )
        }
    }

    /** Read the next chunk record and append it to the list. */
    private suspend fun appendNextPage(deck: Deck) {
        val chunk = pageOrder.getOrNull(pagesLoaded)
        if (chunk == null) {
            _state.update { it.copy(isLoadingCards = false, hasMoreCards = false) }
            return
        }
        cardRepository.readChunk(deck, chunk)
            .onSuccess { page ->
                pagesLoaded++
                _state.update { s ->
                    s.copy(
                        cards = s.cards + page.inStudyOrder().map { it.toEditable() },
                        isLoadingCards = false,
                        hasMoreCards = pagesLoaded < pageOrder.size,
                    )
                }
            }
            .onFailure { err ->
                // hasMoreCards is left alone: the page is still there to try again for, and
                // clearing it would tell the list the deck ends here.
                Log.e(TAG, "appendNextPage: chunk $chunk FAILED — ${err.message}", err)
                _state.update {
                    it.copy(
                        isLoadingCards = false,
                        error = DeckEditorError(DeckEditorOp.LoadCards, err.toErrorReason()),
                    )
                }
            }
    }

    /** The list scrolled near its end — pull in the next chunk. */
    fun onLoadMoreCards() {
        if (pageJob?.isActive == true || loadJob?.isActive == true) return
        if (!_state.value.hasMoreCards) return
        val deck = loadedDeck ?: return
        pageJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingCards = true, error = null) }
            appendNextPage(deck)
        }
    }

    /**
     * Re-read the pages already on screen when the screen returns to the foreground.
     *
     * The card editor is a separate screen writing straight through to the repository, so on the way
     * back this list shows each card as it was before that edit. Only the pages already paged in are
     * re-read, so returning from a card edit does not re-download the rest of a 20k-card deck.
     */
    fun onResume() {
        if (deckId == null || loadJob?.isActive == true) return
        viewModelScope.launch { reloadLoadedPages() }
    }

    private suspend fun reloadLoadedPages() {
        val currentDeckId = deckId ?: return
        val deck = deckRepository.getLocal(currentDeckId) ?: loadedDeck ?: return
        loadedDeck = deck
        val order = deck.chunks.sortedBy { it.n }.map { it.n }
        if (order.isEmpty()) {
            loadWholeDeck(deck)
            return
        }
        // At least one page: a deck whose every card was deleted still has to stop showing them.
        val pages = pagesLoaded.coerceIn(1, order.size)
        val cards = mutableListOf<Card>()
        for (index in 0 until pages) {
            cards += cardRepository.readChunk(deck, order[index]).getOrDefault(emptyList())
        }
        pageOrder = order
        pagesLoaded = pages
        _state.update { s ->
            s.copy(
                cards = cards.inStudyOrder().map { it.toEditable() },
                totalCards = deck.cardCount,
                hasMoreCards = pages < order.size,
            )
        }
    }

    /**
     * Stopped at the cap rather than accepted and rejected on save: the title field is one line that
     * scrolls horizontally, so an over-long title hides its own beginning while being typed and the
     * error names a number the user cannot see themselves approaching. The counter does that job.
     */
    fun onTitleChanged(text: String) {
        val capped = text.take(DeckLimits.TITLE_MAX_LENGTH)
        _state.update { it.copy(title = capped, titleError = titleErrorFor(capped)) }
    }

    fun onDescriptionChanged(text: String) {
        _state.update { it.copy(description = text, descriptionError = descriptionErrorFor(text)) }
    }

    fun onCoverEmojiChanged(emoji: String) {
        _state.update { it.copy(coverEmoji = emoji) }
    }

    fun onAddTag(tag: String) {
        val trimmed = tag.trim().lowercase()
        if (trimmed.isBlank()) return
        // `loopky-*` is Loopky's own index namespace, not a topic (#40) — a hand-entered one would
        // forge a global-browse entry and read as a topical chip on the deck.
        if (ReservedTags.isReserved(trimmed)) {
            Log.d(TAG, "onAddTag: ignoring reserved label '$trimmed'")
            return
        }
        // Dedup: the tag input can be tapped twice with the same label, and a tag record is keyed
        // by label, so a duplicate is a no-op on the homeserver but a second chip in the UI (#83).
        _state.update { s -> if (trimmed in s.tags) s else s.copy(tags = s.tags + trimmed) }
    }

    fun onRemoveTag(tag: String) {
        _state.update { s -> s.copy(tags = s.tags - tag) }
    }

    /**
     * Add a card. On an existing deck this hands straight to the card editor, which writes through
     * `upsertCard` when saved — it cannot be a blank row here any more, because the list no longer
     * holds the whole deck and there is no full publish to sweep such a row into. A deck that does
     * not exist yet has no `upsertCard` to call, so there the blank row is still the only option.
     */
    fun onAddCard() {
        val currentDeckId = deckId
        if (currentDeckId == null) {
            val newCard = EditableCardModel(
                id = generateId(),
                frontText = "",
                backText = "",
                hasImage = false,
                hasAudio = false,
            )
            _state.update { s -> s.copy(cards = s.cards + newCard, totalCards = s.totalCards + 1) }
            return
        }
        viewModelScope.launch { _effects.emit(DeckEditorEffect.NavigateNewCard(currentDeckId)) }
    }

    /**
     * A cover could previously only be set while publishing — the editor's cover box was not
     * tappable, so an existing deck's cover could never be changed. Mirrors the identical pair on
     * [PublishDeckViewModel].
     */
    fun onCoverWebSelected(url: String) {
        _state.update {
            it.copy(
                coverImageUrl = url,
                coverImageBase64 = null,
                coverPendingBytes = null,
                coverPendingMime = null,
            )
        }
    }

    fun onCoverGallerySelected(bytes: ByteArray, mime: String) {
        _state.update {
            it.copy(
                coverImageUrl = null,
                coverImageBase64 = null,
                coverPendingBytes = bytes,
                coverPendingMime = mime,
            )
        }
    }

    /**
     * Move a card one position, or straight to [to] — the affordance a deck too big to drag needs.
     *
     * Persisted immediately through [DeckRepository.moveCard], which rewrites only the chunks the
     * move touches. The list moves first and is put back if the write fails: a reorder that waited
     * on the homeserver would feel broken at every deck size.
     *
     * [from] indexes the loaded list, which is a prefix of the deck, so it is also the card's study
     * position. [to] is a position anywhere in the **deck**, so a destination past the loaded window
     * simply takes the row out of the window.
     */
    fun onMoveCard(from: Int, to: Int) {
        val cards = _state.value.cards
        if (from !in cards.indices) return
        val total = maxOf(_state.value.totalCards, cards.size)
        val target = to.coerceIn(0, (total - 1).coerceAtLeast(0))
        if (target == from) return
        val cardId = cards[from].id
        _state.update { s ->
            if (from !in s.cards.indices) return@update s
            val reordered = s.cards.toMutableList()
            val moved = reordered.removeAt(from)
            if (target < reordered.size || !s.hasMoreCards) {
                reordered.add(target.coerceAtMost(reordered.size), moved)
            }
            s.copy(cards = reordered)
        }

        // No deck yet, so nothing to move on the homeserver — publish writes the order on save.
        val currentDeckId = deckId ?: return
        viewModelScope.launch {
            moveLock.withLock {
                deckRepository.moveCard(currentDeckId, cardId, target)
                    .onSuccess { loadedDeck = it }
                    .onFailure { err ->
                        Log.e(TAG, "onMoveCard: FAILED — ${err.message}", err)
                        _state.update {
                            it.copy(error = DeckEditorError(DeckEditorOp.MoveCard, err.toErrorReason()))
                        }
                        reloadLoadedPages()
                    }
            }
        }
    }

    fun onCardClick(cardId: String) {
        val currentDeckId = deckId ?: return
        viewModelScope.launch { _effects.emit(DeckEditorEffect.NavigateEditCard(currentDeckId, cardId)) }
    }

    fun onCloseClick() {
        viewModelScope.launch { _effects.emit(DeckEditorEffect.NavigateBack) }
    }

    fun onToggleListen() {
        _state.update { it.copy(listenEnabled = !it.listenEnabled, languagesError = null) }
    }

    fun onToggleSpeak() {
        _state.update { it.copy(speakEnabled = !it.speakEnabled, languagesError = null) }
    }

    /** No `languagesError` to clear: typing needs no language pair. See `Deck.speechReady`. */
    fun onToggleType() {
        _state.update { it.copy(typeEnabled = !it.typeEnabled) }
    }

    /** Nor does asking the card backwards — swapping two sides needs no declared language. */
    fun onToggleReverse() {
        _state.update { it.copy(reverseEnabled = !it.reverseEnabled) }
    }

    /**
     * Picking a language also labels the deck with it — `"spanish"`, an ordinary tag the author can
     * remove — so a stranger learning that language can find the deck. The label the previous pick
     * contributed goes in the same step; see [LanguageTags.retag].
     */
    fun onFrontLangSelected(tag: String) {
        _state.update {
            it.copy(
                frontLang = tag,
                languagesError = null,
                tags = LanguageTags.retag(it.tags, it.frontLang, it.backLang, tag, it.backLang),
            )
        }
    }

    fun onBackLangSelected(tag: String) {
        _state.update {
            it.copy(
                backLang = tag,
                languagesError = null,
                tags = LanguageTags.retag(it.tags, it.frontLang, it.backLang, it.frontLang, tag),
            )
        }
    }

    /**
     * Field checks that must pass before anything is written. Extracted from [onSaveClick] so the
     * save body stays about saving.
     */
    private fun validateForSave(s: DeckEditorUiState): Boolean {
        if (s.title.isBlank()) {
            _state.update { it.copy(titleError = FormError.TitleRequired) }
            return false
        }
        val titleError = titleErrorFor(s.title)
        val descriptionError = descriptionErrorFor(s.description)
        // Saving listen/speak without the pair leaves the deck's audio wrong on every device but
        // this one, so it is refused rather than defaulted from the device locale.
        val languagesError =
            if (s.speechLanguagesMissing) FormError.LanguagesRequired else null
        if (titleError != null || descriptionError != null || languagesError != null) {
            _state.update {
                it.copy(
                    titleError = titleError,
                    descriptionError = descriptionError,
                    languagesError = languagesError,
                )
            }
            return false
        }
        return true
    }

    fun onSaveClick() {
        if (saveJob?.isActive == true) return
        val s = _state.value
        if (!validateForSave(s)) return
        saveJob = viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            Log.d(TAG, "save: title=${s.title}, cards=${s.cards.size}")

            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val authorPubky = session?.identity?.pubky ?: run {
                _state.update {
                    it.copy(
                        isSaving = false,
                        error = DeckEditorError(DeckEditorOp.Save, ErrorReason.NotSignedIn),
                    )
                }
                return@launch
            }

            val now = epochMillis()
            val actualDeckId = deckId ?: generateId()
            // Re-read rather than trusting the snapshot this screen opened with: a card written
            // from the card editor has moved the chunk table, and writing the old one back would
            // orphan the chunk it patched.
            val existing = deckId?.let { deckRepository.getLocal(it) ?: loadedDeck }
            val cards = if (deckId == null) newDeckCards(s.cards, actualDeckId, now) else emptyList()
            val cover = resolveCoverImage(s, actualDeckId, mediaRepository, existing?.coverImageRef)
                ?: existing?.coverImageRef
            val deck = buildDeck(s, authorPubky, actualDeckId, existing, cards, now, cover)

            writeDeck(deck, cards, isCreate = deckId == null)
                .onSuccess { saved ->
                    Log.d(TAG, "save: SUCCESS deckId=$actualDeckId")
                    loadedDeck = saved
                    _state.update { it.copy(isSaving = false) }
                    settle(saved, isCreate = deckId == null)
                }
                .onFailure { err ->
                    // The reason, never `err.message`: this is where the FFI's own
                    // "Failed to import session: Request failed: HTTP transport error: error
                    // sending request for url (https://_pubky.…/session)" was rendered to users,
                    // in the card list, as the only place the real cause appeared at all (#165).
                    Log.e(TAG, "save: FAILED — ${err.message}", err)
                    _state.update {
                        it.copy(
                            isSaving = false,
                            error = DeckEditorError(DeckEditorOp.Save, err.toErrorReason()),
                        )
                    }
                }
        }
    }

    /**
     * The escape hatch offered beside a session failure: end the session and go sign in again.
     *
     * Explicit, never automatic. A `SessionUnreachable` write failed without the homeserver ever
     * answering, so the app has no grounds to end a session that may still be good — but a fresh
     * sign-in was the only thing that ever cleared it on device (#165).
     *
     * The sign-out is best-effort: `signOut` refuses when it would destroy the only copy of an
     * un-backed-up local key, and that refusal must stand — but it is no reason to withhold the
     * sign-in screen, since signing in again *replaces* the session without clearing the old one.
     */
    fun onSignInAgainClick() {
        viewModelScope.launch {
            Log.d(TAG, "onSignInAgainClick: signing out to re-authenticate")
            runSuspendCatching { identityRepository.signOut() }
            _effects.emit(DeckEditorEffect.NavigateToOnboarding)
        }
    }

    /** Clear a failure the user has read, so the next attempt starts from a clean screen. */
    fun onDismissError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Leave the editor, offering to announce the deck first when this save *created* it (#39).
     * [isCreate] keys off the constructor's `deckId` being null, the editor's only honest "new deck"
     * signal — announcing unconditionally would post again every time someone fixed a typo.
     */
    private suspend fun settle(deck: Deck, isCreate: Boolean) {
        savedDeckId = deck.id
        if (isCreate && appPreferences.shareOnPubky.first()) {
            _state.update {
                it.copy(
                    sharePrompt = DeckSharePrompt(
                        DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created),
                    ),
                )
            }
            return
        }
        _effects.emit(DeckEditorEffect.SaveSuccess(deck.id))
    }

    /** Post the announcement, then leave regardless — a failed post is not a failed save. */
    fun onShareConfirm() {
        val prompt = _state.value.sharePrompt?.takeIf { !it.isPosting } ?: return
        viewModelScope.launch {
            _state.update { it.copy(sharePrompt = prompt.copy(isPosting = true)) }
            discoveryRepository.announceDeck(prompt.announcement)
                .onSuccess { _effects.emit(DeckEditorEffect.Shared) }
                .onFailure { err ->
                    Log.e(TAG, "share: FAILED — ${err.message}", err)
                    _effects.emit(DeckEditorEffect.ShareFailed)
                }
            dismissSharePrompt()
        }
    }

    fun onShareDismiss() {
        _state.value.sharePrompt?.takeIf { !it.isPosting } ?: return
        viewModelScope.launch { dismissSharePrompt() }
    }

    /** Declines *and* turns the offer off, so the prompt and the Settings switch stay one setting. */
    fun onShareNeverAsk() {
        _state.value.sharePrompt?.takeIf { !it.isPosting } ?: return
        viewModelScope.launch {
            appPreferences.setShareOnPubky(false)
            dismissSharePrompt()
        }
    }

    private suspend fun dismissSharePrompt() {
        val savedId = savedDeckId ?: return
        _state.update { it.copy(sharePrompt = null) }
        _effects.emit(DeckEditorEffect.SaveSuccess(savedId))
    }

    /**
     * Persist the deck. An existing one writes **only** its manifest: republishing would rewrite
     * every chunk — ~201 requests to change one field on a 20k-card deck — and, now that the card
     * list is a page rather than the deck, would write back only the cards this screen has read.
     * Card changes are already written by the time Save is tapped.
     */
    private suspend fun writeDeck(deck: Deck, cards: List<Card>, isCreate: Boolean): Result<Deck> =
        if (isCreate) {
            Log.d(TAG, "save: publish deckId=${deck.id} cards=${cards.size}")
            deckRepository.publish(deck, cards)
        } else {
            Log.d(TAG, "save: metadata-only deckId=${deck.id}")
            deckRepository.updateMetadata(deck)
        }

    companion object {
        private const val TAG = "Loopky/DeckEditorVM"

        /**
         * Above this the card list stops offering drag-to-reorder: dragging one row across thousands
         * is not a usable gesture, and the list it would drag through is not even loaded. "Move to
         * position…" is the affordance at that size (#52).
         */
        const val DRAG_REORDER_LIMIT = 100
    }
}

private const val DEFAULT_IMAGE_MIME = "image/jpeg"

private fun titleErrorFor(text: String): FormError? =
    if (text.length > DeckLimits.TITLE_MAX_LENGTH) {
        FormError.TitleTooLong
    } else {
        null
    }

private fun descriptionErrorFor(text: String): FormError? =
    if (text.length > DeckLimits.DESCRIPTION_MAX_LENGTH) {
        FormError.DescriptionTooLong
    } else {
        null
    }

private fun Card.toEditable(): EditableCardModel = EditableCardModel(
    id = id,
    frontText = front.text ?: "",
    backText = back.text ?: "",
    hasImage = front.imageRef != null || back.imageRef != null,
    hasAudio = front.audioRef != null || back.audioRef != null,
)

/** Uploads picked gallery bytes or wraps a web URL; null means "keep whatever is there". */
private suspend fun resolveCoverImage(
    s: DeckEditorUiState,
    deckId: String,
    mediaRepository: MediaRepository,
    existing: MediaRef.Image?,
): MediaRef.Image? = when {
    s.coverPendingBytes != null ->
        mediaRepository.putImage(deckId, s.coverPendingBytes, s.coverPendingMime ?: DEFAULT_IMAGE_MIME)
            .getOrNull()

    // Only a URL the user actually picked builds a new ref. Since #166 the editor opens with the
    // stored cover's URL already in state, and rebuilding from it would throw away whatever the
    // saved ref carries beyond the URL (its mime, its dimensions) on every metadata save.
    s.coverImageUrl != null && s.coverImageUrl != existing?.url -> remoteImageRef(s.coverImageUrl)

    else -> null
}

/**
 * The cards a not-yet-published deck will be created with. Rows with nothing on either side are
 * dropped rather than published: `publish` rejects an empty side, so an untouched "Add card" row
 * would otherwise fail the whole save.
 */
private fun newDeckCards(
    editables: List<EditableCardModel>,
    deckId: String,
    now: Long,
): List<Card> = editables.mapNotNull { editable ->
    val front = CardSide(text = editable.frontText.ifBlank { null })
    val back = CardSide(text = editable.backText.ifBlank { null })
    if (front.isEmpty || back.isEmpty) return@mapNotNull null
    Card(id = editable.id, deckId = deckId, updatedAt = now, front = front, back = back)
}

/** [existing] supplies the fields this editor does not expose, so a save cannot destroy them. */
@Suppress("LongParameterList")
private fun buildDeck(
    s: DeckEditorUiState,
    authorPubky: String,
    deckId: String,
    existing: Deck?,
    cards: List<Card>,
    now: Long,
    coverImageRef: MediaRef.Image?,
): Deck = Deck(
    id = deckId,
    authorPubky = authorPubky,
    title = s.title,
    description = s.description.ifBlank { null },
    coverEmoji = s.coverEmoji.ifBlank { null },
    coverImageRef = coverImageRef,
    tags = s.tags.map { Tag(it) },
    createdAt = if (s.isNew) now else existing?.createdAt ?: now,
    updatedAt = now,
    // The card set is the chunk table's business, never this screen's: on a create publish()
    // recomputes both from the cards it writes, and on an edit these are carried through
    // untouched so a metadata save cannot orphan a chunk a card write just added.
    cardCount = existing?.cardCount ?: cards.size,
    chunks = existing?.chunks.orEmpty(),
    source = existing?.source,
    listenEnabled = s.listenEnabled,
    speakEnabled = s.speakEnabled,
    typeEnabled = s.typeEnabled,
    reverseEnabled = s.reverseEnabled,
    frontLang = s.frontLang,
    backLang = s.backLang,
    mediaRehostCursor = existing?.mediaRehostCursor ?: 0,
    mediaRehosted = existing?.mediaRehosted ?: false,
)

data class DeckEditorUiState(
    val isNew: Boolean = true,
    val coverEmoji: String = "",
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    /** The cards paged in so far — a prefix of the deck, not the deck. See [totalCards]. */
    val cards: List<EditableCardModel> = emptyList(),
    /** Cards in the whole deck, from the manifest. What the header counts. */
    val totalCards: Int = 0,
    /** A page is in flight. */
    val isLoadingCards: Boolean = false,
    /** There are chunk records left to page in. */
    val hasMoreCards: Boolean = false,
    /** A remote cover: the deck's stored URL on open, or one picked this session. */
    val coverImageUrl: String? = null,
    /** A homeserver-blob cover's bytes, fetched on open. Null for a remote or unset cover. */
    val coverImageBase64: String? = null,
    val coverPendingBytes: ByteArray? = null,
    val coverPendingMime: String? = null,
    val isSaving: Boolean = false,
    /** Off until asked for — see the same field on `PublishDeckUiState`. */
    val listenEnabled: Boolean = false,
    val speakEnabled: Boolean = false,
    /** Off until asked for as well, though for its own reason — see `Deck.typeEnabled`. */
    val typeEnabled: Boolean = false,
    /** Same again — see `Deck.reverseEnabled`. Editable here so a deck published before the
     * opt-in existed can still be given it. */
    val reverseEnabled: Boolean = false,
    /** BCP-47 tags for the two card sides; required once either speech opt-in above is on. */
    val frontLang: String? = null,
    val backLang: String? = null,
    val languagesError: FormError? = null,
    val titleError: FormError? = null,
    val descriptionError: FormError? = null,
    /** What failed and why, never the FFI's own words — see [DeckEditorError]. */
    val error: DeckEditorError? = null,
    /** Set after a save that created the deck, unless the user has opted out of being asked (#39). */
    val sharePrompt: DeckSharePrompt? = null,
) {
    /**
     * Whether the list should offer the drag handle. False once the deck is big enough that a drag
     * would have to cross rows that are not loaded — "move to position…" is the affordance there.
     */
    val canDragReorder: Boolean
        get() = !hasMoreCards && totalCards <= DeckEditorViewModel.DRAG_REORDER_LIMIT

    /** Listen or Speak is on, but the deck cannot yet say what language to use. */
    val speechLanguagesMissing: Boolean
        get() = SpeechLanguages.isPairMissing(listenEnabled, speakEnabled, frontLang, backLang)
}

data class EditableCardModel(
    val id: String,
    val frontText: String,
    val backText: String,
    val hasImage: Boolean,
    val hasAudio: Boolean,
)

/**
 * What failed in the editor, and why.
 *
 * Two fields rather than one message because the *consequence* differs and the *cause* does not: a
 * failed card page still lets the deck's details save, a failed move leaves the list showing an order
 * the homeserver does not have, and a failed save wrote nothing. The reason beside it is the
 * vocabulary every other screen speaks.
 *
 * It replaced a raw `String` carrying `err.message`, which is how the card list came to render
 * `"Failed to import session: Request failed: HTTP transport error…"` in place of the cards (#165).
 */
data class DeckEditorError(val op: DeckEditorOp, val reason: ErrorReason)

/** The editor operations that can fail with an [ErrorReason]. */
enum class DeckEditorOp {
    /** A page of the card list did not arrive. The metadata is still editable and still saves. */
    LoadCards,

    /** A reorder was rejected; the list has been re-read from the homeserver. */
    MoveCard,

    /** The manifest write failed, so nothing was saved. */
    Save,
}

sealed interface DeckEditorEffect {
    data object NavigateBack : DeckEditorEffect

    /** The user chose to sign in again from a session failure; the session is already cleared. */
    data object NavigateToOnboarding : DeckEditorEffect
    data class NavigateEditCard(val deckId: String, val cardId: String) : DeckEditorEffect

    /** Open the card editor on a card that does not exist yet; it writes it on save. */
    data class NavigateNewCard(val deckId: String) : DeckEditorEffect
    data class SaveSuccess(val deckId: String) : DeckEditorEffect

    /** The announcement post went out, or didn't. Cosmetic either way — the deck is saved. */
    data object Shared : DeckEditorEffect
    data object ShareFailed : DeckEditorEffect
}
