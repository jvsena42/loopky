package com.github.jvsena42.loopky.presentation.importflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.DeckLimits
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.FormError
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.LanguageTags
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.SpeechLanguages
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.frontBackOf
import com.github.jvsena42.loopky.domain.model.remoteImageRef
import com.github.jvsena42.loopky.presentation.share.DeckSharePrompt
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.generateId
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class PublishDeckViewModel(
    private val importRepository: ImportRepository,
    private val deckRepository: DeckRepository,
    private val identityRepository: IdentityRepository,
    private val mediaRepository: MediaRepository,
    private val discoveryRepository: DiscoveryRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(PublishDeckUiState())
    val state: StateFlow<PublishDeckUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PublishDeckEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<PublishDeckEffect> = _effects.asSharedFlow()

    private var publishJob: Job? = null
    private var undoCountdownJob: Job? = null

    /** The deck id of the publish in flight, so a cancel knows what to sweep. */
    private var publishingDeckId: String? = null

    /**
     * The deck as published, kept for the share prompt — an announcement quotes the title, the
     * cover and the manifest URI, none of which the UI state carries in one piece. Cleared by an
     * undo so a deck the user took back can never be announced.
     */
    private var publishedDeck: Deck? = null

    init {
        val draft = importRepository.currentDraft()
        if (draft != null) {
            val kept = importRepository.keptRows().size
            _state.update {
                it.copy(
                    // A file import knows a good title — the .apkg's deck name, else the file it
                    // came from — and used to throw it away. A paste has none, so this stays "".
                    title = draft.suggestedTitle.orEmpty(),
                    // Prefilled, not decided: an Anki deck's own description and the labels its
                    // notes were tagged with are a starting point the user edits, and the source
                    // that proposed them is not describing the deck the way they would.
                    description = draft.suggestedDescription.orEmpty(),
                    tags = draft.suggestedTags.mapNotNull(::normalizeTag).distinct(),
                    cardCount = kept,
                    discardedCount = draft.rows.size - kept,
                    reverseEnabled = draft.suggestsReverse,
                )
            }
        }
    }

    /** Stopped at the cap, for the reason on `DeckEditorViewModel.onTitleChanged`. */
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
     * Picking a language also labels the deck with it — `"spanish"`, an ordinary tag the author
     * can still remove — so a stranger learning that language can find the deck. The label the
     * previous pick contributed goes in the same step; see [LanguageTags.retag].
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

    /** A web (Unsplash) cover image was chosen — saved by URL, no upload. */
    fun onCoverWebSelected(url: String) {
        _state.update { it.copy(coverImageUrl = url, coverPendingBytes = null, coverPendingMime = null) }
    }

    /** A gallery cover image was chosen — already compressed; uploaded on publish. */
    fun onCoverGallerySelected(bytes: ByteArray, mime: String) {
        _state.update { it.copy(coverImageUrl = null, coverPendingBytes = bytes, coverPendingMime = mime) }
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

    /**
     * A tag label as the publish flow will accept it, or null. Shares [onAddTag]'s rules so a
     * suggested chip cannot arrive in a state the user could not have typed.
     */
    private fun normalizeTag(tag: String): String? =
        tag.trim().lowercase().takeIf { it.isNotBlank() && !ReservedTags.isReserved(it) }

    fun onRemoveTag(tag: String) {
        _state.update { s -> s.copy(tags = s.tags - tag) }
    }

    fun onBackClick() {
        viewModelScope.launch { _effects.emit(PublishDeckEffect.NavigateBack) }
    }

    fun onPublishClick() {
        if (publishJob?.isActive == true || _state.value.isCancelling) return
        val s = _state.value
        if (!validateForPublish(s)) return

        val draft = importRepository.currentDraft()
        if (draft == null) {
            _state.update { it.copy(error = PublishError.NoDraft) }
            return
        }

        publishJob = viewModelScope.launch {
            // publishedCardCount is reset here so a retry after a failure does not open on the
            // count the failed run reached.
            _state.update {
                it.copy(isPublishing = true, error = null, publishProgress = 0f, publishedCardCount = 0)
            }
            Log.d(TAG, "publish: title=${s.title}, cards=${importRepository.keptRows().size}")

            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val authorPubky = session?.identity?.pubky ?: run {
                _state.update {
                    it.copy(isPublishing = false, error = PublishError.Publish(ErrorReason.NotSignedIn))
                }
                return@launch
            }

            val now = epochMillis()
            val deckId = generateId()
            // Recorded before the first write so a cancel knows what to sweep. publish() writes a
            // marker manifest first (#49), so anything from here on is reachable and deletable.
            publishingDeckId = deckId
            val (cards, coverImageRef) = prepareContent(draft, s, deckId, now) ?: return@launch

            val deck = s.toDeck(deckId, authorPubky, coverImageRef, cards, now)

            publishWithProgress(deck, cards)
                .onSuccess {
                    Log.d(TAG, "publish: SUCCESS deckId=$deckId")
                    publishingDeckId = null
                    publishedDeck = deck
                    _state.update {
                        it.copy(
                            isPublishing = false,
                            publishProgress = null,
                            publishedDeckId = deckId,
                            undoSecondsRemaining = UNDO_WINDOW_SECONDS,
                        )
                    }
                    startUndoCountdown(deckId)
                }
                .onFailure { err ->
                    // Only real failures reach here: publish() rethrows cancellation, so a
                    // user-initiated cancel kills this coroutine and leaves the state to
                    // onCancelPublish rather than overwriting it with "…was cancelled".
                    Log.e(TAG, "publish: FAILED — ${err.message}", err)
                    // Left for the user to retry over: chunk PUTs are idempotent, and the marker
                    // manifest keeps a half-written deck reachable rather than orphaned.
                    publishingDeckId = null
                    _state.update {
                        it.copy(
                            isPublishing = false,
                            publishProgress = null,
                            error = PublishError.Publish(err.toErrorReason()),
                        )
                    }
                }
        }
    }

    /**
     * The cards and cover to publish, with every attached image uploaded — or null when an upload
     * failed, in which case the error is already in the state and the caller must stop.
     *
     * Media goes up before the marker manifest exists, so a failure here cannot be swept by
     * [DeckRepository.delete], which needs a manifest to walk. The refs are tracked as they land.
     */
    private suspend fun prepareContent(
        draft: ImportDraft,
        s: PublishDeckUiState,
        deckId: String,
        now: Long,
    ): Pair<List<Card>, MediaRef.Image?>? {
        val uploaded = mutableListOf<MediaRef>()
        return runSuspendCatching {
            val cards = buildCards(draft, deckId, now, uploaded)
            cards to resolveCoverImage(s, deckId, uploaded)
        }.getOrElse { err ->
            Log.e(TAG, "publish: media upload FAILED — ${err.message}", err)
            sweepUploadedMedia(deckId, uploaded)
            publishingDeckId = null
            _state.update {
                it.copy(isPublishing = false, error = PublishError.Publish(err.toErrorReason()))
            }
            null
        }
    }

    private fun PublishDeckUiState.toDeck(
        deckId: String,
        authorPubky: String,
        coverImageRef: MediaRef.Image?,
        cards: List<Card>,
        now: Long,
    ) = Deck(
        id = deckId,
        authorPubky = authorPubky,
        title = title,
        description = description.ifBlank { null },
        coverEmoji = coverEmoji.ifBlank { null },
        coverImageRef = coverImageRef,
        tags = tags.map { Tag(it) },
        createdAt = now,
        updatedAt = now,
        // publish() writes the chunk table; this is the optimistic count it confirms.
        cardCount = cards.size,
        source = DeckSource(kind = DeckSource.Kind.Import, importedAt = now),
        listenEnabled = listenEnabled,
        speakEnabled = speakEnabled,
        typeEnabled = typeEnabled,
        reverseEnabled = reverseEnabled,
        frontLang = frontLang,
        backLang = backLang,
    )

    /**
     * A 20k-card import is ~201 uploads; a bare spinner cannot say how far along that is, so the
     * repository's progress is mirrored into the UI state as it arrives.
     */
    private suspend fun publishWithProgress(deck: Deck, cards: List<Card>) =
        deckRepository.publish(deck, cards) { progress ->
            _state.update {
                // Not nulled on `done`: that made the bar blank for a frame at ~99% instead of
                // filling. `isPublishing = false` is what removes the whole block.
                it.copy(
                    publishProgress = progress.fraction,
                    publishedCardCount = progress.cardsWritten,
                )
            }
        }

    /**
     * Stop a publish in flight and sweep whatever reached the homeserver.
     *
     * Offerable at all because of #49's marker manifest, written before the first chunk, so an
     * interrupted publish leaves a deck that is reachable and deletable. But a deck the user just took
     * back has no business staying in their library, so it is deleted rather than left as an
     * `incomplete` husk. If the sweep itself fails — offline being a likely reason to cancel a stalled
     * upload — the husk stays, which is the case the marker exists for.
     */
    fun onCancelPublish() {
        val job = publishJob?.takeIf { it.isActive } ?: return
        val deckId = publishingDeckId
        publishJob = null
        _state.update {
            it.copy(
                isPublishing = false,
                isCancelling = deckId != null,
                publishProgress = null,
                error = null,
            )
        }
        viewModelScope.launch {
            // cancelAndJoin, not cancel: the publish coroutine must be finished before the sweep
            // starts, or a chunk PUT still in flight lands after delete() walked the listing.
            job.cancelAndJoin()
            if (deckId == null) return@launch
            Log.d(TAG, "cancel: sweeping partial deckId=$deckId")
            deckRepository.delete(deckId)
                .onSuccess {
                    Log.d(TAG, "cancel: swept deckId=$deckId")
                    _state.update { it.copy(isCancelling = false) }
                }
                .onFailure { err ->
                    Log.e(TAG, "cancel: sweep FAILED — ${err.message}", err)
                    _state.update {
                        it.copy(isCancelling = false, error = PublishError.Cancel(err.toErrorReason()))
                    }
                }
            publishingDeckId = null
        }
    }

    fun onUndoPublish() {
        val deckId = _state.value.publishedDeckId ?: return
        undoCountdownJob?.cancel()
        viewModelScope.launch {
            Log.d(TAG, "undo: deleting deckId=$deckId")
            deckRepository.delete(deckId)
                .onSuccess {
                    Log.d(TAG, "undo: SUCCESS deckId=$deckId")
                    publishedDeck = null
                    _state.update {
                        it.copy(publishedDeckId = null, undoSecondsRemaining = 0, error = null)
                    }
                }
                .onFailure { err ->
                    Log.e(TAG, "undo: FAILED — ${err.message}", err)
                    _state.update { it.copy(error = PublishError.Undo(err.toErrorReason())) }
                }
        }
    }

    /**
     * The escape hatch beside a session failure: end the session and go sign in again.
     *
     * Explicit, never automatic — a `SessionUnreachable` publish failed without the homeserver ever
     * answering, so ending a session that may still be good is the user's call. The draft survives:
     * it lives in the import repository, not this screen (#165).
     *
     * The sign-out is best-effort: `signOut` refuses when it would destroy the only copy of an
     * un-backed-up local key, and that refusal must stand — but it is no reason to withhold the
     * sign-in screen, since signing in again *replaces* the session without clearing the old one.
     */
    fun onSignInAgainClick() {
        viewModelScope.launch {
            Log.d(TAG, "onSignInAgainClick: signing out to re-authenticate")
            runSuspendCatching { identityRepository.signOut() }
            _effects.emit(PublishDeckEffect.NavigateToOnboarding)
        }
    }

    fun onDonePublish() {
        val deckId = _state.value.publishedDeckId ?: return
        undoCountdownJob?.cancel()
        viewModelScope.launch { settle(deckId) }
    }

    private fun startUndoCountdown(deckId: String) {
        undoCountdownJob?.cancel()
        undoCountdownJob = viewModelScope.launch {
            var remaining = UNDO_WINDOW_SECONDS
            while (remaining > 0) {
                delay(COUNTDOWN_TICK_MS)
                remaining -= 1
                _state.update { it.copy(undoSecondsRemaining = remaining) }
            }
            settle(deckId)
        }
    }

    /**
     * The undo window is over, so the deck is real and the user can be asked about announcing it (#39).
     * Deliberately not asked *during* the window: a post about a deck deleted a second later advertises
     * nothing. Undo clears [publishedDeck], so the path reaching here after an undo asks nothing.
     */
    private suspend fun settle(deckId: String) {
        importRepository.clear()
        val deck = publishedDeck
        if (deck != null && appPreferences.shareOnPubky.first()) {
            _state.update {
                it.copy(
                    sharePrompt = DeckSharePrompt(
                        DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created),
                    ),
                )
            }
            return
        }
        _effects.emit(PublishDeckEffect.Published(deckId))
    }

    /** Post the announcement, then leave regardless — a failed post is not a failed publish. */
    fun onShareConfirm() {
        val prompt = _state.value.sharePrompt?.takeIf { !it.isPosting } ?: return
        viewModelScope.launch {
            _state.update { it.copy(sharePrompt = prompt.copy(isPosting = true)) }
            discoveryRepository.announceDeck(prompt.announcement)
                .onSuccess { _effects.emit(PublishDeckEffect.Shared) }
                .onFailure { err ->
                    Log.e(TAG, "share: FAILED — ${err.message}", err)
                    _effects.emit(PublishDeckEffect.ShareFailed)
                }
            dismissSharePrompt()
        }
    }

    fun onShareDismiss() {
        if (_state.value.sharePrompt?.isPosting == true) return
        viewModelScope.launch { dismissSharePrompt() }
    }

    /** Declines *and* turns the offer off, so the prompt and the Settings switch stay one setting. */
    fun onShareNeverAsk() {
        if (_state.value.sharePrompt?.isPosting == true) return
        viewModelScope.launch {
            appPreferences.setShareOnPubky(false)
            dismissSharePrompt()
        }
    }

    private suspend fun dismissSharePrompt() {
        val deckId = _state.value.publishedDeckId
        _state.update { it.copy(sharePrompt = null) }
        deckId?.let { _effects.emit(PublishDeckEffect.Published(it)) }
    }

    /**
     * Maps the kept triage rows to [Card]s, uploading any per-row images attached during triage.
     * Throws if an upload fails — see [resolveDraftImage]. Every ref that did land is appended to
     * [uploaded] first, so the caller can remove them.
     */
    private suspend fun buildCards(
        draft: ImportDraft,
        deckId: String,
        now: Long,
        uploaded: MutableList<MediaRef>,
    ): List<Card> {
        val cards = mutableListOf<Card>()
        for (row in importRepository.keptRows()) {
            val (front, back) = draft.frontBackOf(row)
            cards.add(
                Card(
                    id = generateId(),
                    deckId = deckId,
                    updatedAt = now,
                    front = CardSide(
                        text = front.takeIf { it.isNotBlank() },
                        imageRef = resolveDraftImage(
                            importRepository.rowImage(row.index, isFront = true),
                            deckId,
                            uploaded,
                        ),
                    ),
                    back = CardSide(
                        text = back.takeIf { it.isNotBlank() },
                        imageRef = resolveDraftImage(
                            importRepository.rowImage(row.index, isFront = false),
                            deckId,
                            uploaded,
                        ),
                    ),
                ),
            )
        }
        return cards
    }

    /**
     * Resolves a triage [DraftCardImage]: upload gallery bytes, wrap a web URL, else none.
     *
     * **Throws on a failed upload rather than degrading to `null`.** Swallowing it meant a full quota
     * produced a deck that looked successfully published and was quietly missing the images the user
     * picked. A deck missing media the user chose is a failed publish (#91).
     */
    private suspend fun resolveDraftImage(
        image: DraftCardImage?,
        deckId: String,
        uploaded: MutableList<MediaRef>,
    ): MediaRef.Image? = when {
        image == null -> null
        image.bytes != null ->
            mediaRepository.putImage(deckId, image.bytes, image.mime ?: "image/jpeg")
                .onFailure { Log.e(TAG, "card image upload failed — ${it.message}", it) }
                .getOrThrow()
                .also { uploaded.add(it) }

        image.url != null -> remoteImageRef(image.url)

        else -> null
    }

    /** Builds the cover [MediaRef.Image]. Throws on a failed upload, for [resolveDraftImage]'s reason. */
    private suspend fun resolveCoverImage(
        s: PublishDeckUiState,
        deckId: String,
        uploaded: MutableList<MediaRef>,
    ): MediaRef.Image? = when {
        s.coverPendingBytes != null ->
            mediaRepository.putImage(deckId, s.coverPendingBytes, s.coverPendingMime ?: "image/jpeg")
                .onFailure { Log.e(TAG, "cover upload failed — ${it.message}", it) }
                .getOrThrow()
                .also { uploaded.add(it) }

        s.coverImageUrl != null -> remoteImageRef(s.coverImageUrl)

        else -> null
    }

    /**
     * Remove the blobs an aborted publish already uploaded.
     *
     * By hand rather than through `deckRepository.delete`, which walks the manifest — and there is
     * none yet, because media goes up before publish() writes the #49 marker. Best-effort: the publish
     * quite likely aborted because storage is what ran out.
     */
    private suspend fun sweepUploadedMedia(deckId: String, uploaded: List<MediaRef>) {
        for (ref in uploaded) {
            mediaRepository.delete(deckId, ref)
                .onFailure { Log.e(TAG, "sweep: ${ref.sha256} not removed — ${it.message}", it) }
        }
    }

    /**
     * Reachable again now that the button is enabled. It used to be dead code: the screen
     * passed `enabled = state.canPublish`, and `canPublish` was false in exactly the cases
     * this checks — so tapping Publish with an empty title did nothing and said nothing.
     */
    private fun validateForPublish(s: PublishDeckUiState): Boolean {
        val titleError = when {
            s.title.isBlank() -> FormError.TitleRequired
            else -> titleErrorFor(s.title)
        }
        val descriptionError = descriptionErrorFor(s.description)
        // Publishing listen/speak without the pair ships a deck whose audio is wrong on every
        // device but this one, so it is refused rather than defaulted from the device locale.
        val languagesError = languagesErrorFor(s)
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

    private fun languagesErrorFor(s: PublishDeckUiState): FormError? =
        if (s.speechLanguagesMissing) FormError.LanguagesRequired else null

    private fun titleErrorFor(text: String): FormError? =
        if (text.length > DeckLimits.TITLE_MAX_LENGTH) FormError.TitleTooLong else null

    private fun descriptionErrorFor(text: String): FormError? =
        if (text.length > DeckLimits.DESCRIPTION_MAX_LENGTH) FormError.DescriptionTooLong else null

    companion object {
        private const val TAG = "Loopky/PublishVM"
        private const val UNDO_WINDOW_SECONDS = 10
        private const val COUNTDOWN_TICK_MS = 1_000L

        /** Internal so a prefilled title can be capped to it rather than duplicating the number. */
        internal const val TITLE_MAX_LENGTH = DeckLimits.TITLE_MAX_LENGTH
    }
}

data class PublishDeckUiState(
    val title: String = "",
    val description: String = "",
    val coverEmoji: String = "",
    val tags: List<String> = emptyList(),
    val cardCount: Int = 0,
    val discardedCount: Int = 0,
    val isPublishing: Boolean = false,
    /** True from the moment a cancel is confirmed until the partial deck has been swept. */
    val isCancelling: Boolean = false,
    /**
     * 0f..1f while uploading, null when not publishing or when the count is too small to be worth
     * a determinate bar. Lets a large import show real progress instead of an indefinite spinner.
     */
    val publishProgress: Float? = null,
    /** Cards uploaded so far, for a "1,200 of 18,432" label alongside the bar. */
    val publishedCardCount: Int = 0,
    val publishedDeckId: String? = null,
    val undoSecondsRemaining: Int = 0,
    /** Set once the undo window resolves, when the user has not opted out of being asked (#39). */
    val sharePrompt: DeckSharePrompt? = null,
    /**
     * Off until asked for, because turning either on obliges the author to say what language each side
     * is in. Defaulting them on would put that choice in front of everyone importing a deck, and the
     * old default only looked free because the features quietly used the author's own locale.
     */
    val listenEnabled: Boolean = false,
    val speakEnabled: Boolean = false,
    /** Off until asked for as well, though for its own reason — see `Deck.typeEnabled`. */
    val typeEnabled: Boolean = false,
    /**
     * Prefilled from the import when the source said so — an `.apkg` built on a reversed note type
     * arrives with this on. A suggestion like the title and tags beside it, not a decision: the
     * toggle is right there to turn off.
     */
    val reverseEnabled: Boolean = false,
    /** BCP-47 tags for the two card sides; required once either speech opt-in above is on. */
    val frontLang: String? = null,
    val backLang: String? = null,
    val languagesError: FormError? = null,
    val coverImageUrl: String? = null,
    val coverPendingBytes: ByteArray? = null,
    val coverPendingMime: String? = null,
    val titleError: FormError? = null,
    val descriptionError: FormError? = null,
    val error: PublishError? = null,
) {
    /** Listen or Speak is on, but the deck cannot yet say what language to use. */
    val speechLanguagesMissing: Boolean
        get() = SpeechLanguages.isPairMissing(listenEnabled, speakEnabled, frontLang, backLang)

    val canPublish: Boolean
        get() = title.isNotBlank() && titleError == null && descriptionError == null &&
            !isPublishing && !isCancelling && !speechLanguagesMissing
}

/**
 * What went wrong on the publish screen, in terms the UI can speak about.
 *
 * The three failing steps are kept apart rather than folded into one [ErrorReason], because the
 * consequence differs and the copy has to say which: a failed publish leaves nothing, a failed cancel
 * leaves the partial deck on the homeserver, and a failed undo leaves the deck published.
 *
 * Replaces a raw `err.message`, which put the FFI's diagnostic text straight into the UI.
 */
sealed interface PublishError {
    /**
     * The classified cause, for a screen deciding what to offer beside the message. Null only when
     * the failure never involved the homeserver.
     */
    val reason: ErrorReason?

    /** The triage draft is gone, so there is nothing to publish. Not a homeserver failure. */
    data object NoDraft : PublishError {
        override val reason: ErrorReason? = null
    }

    /** The publish itself failed. Nothing was kept; the user can retry over it. */
    data class Publish(override val reason: ErrorReason) : PublishError

    /** The sweep after a cancel failed, so the partial deck is still on the homeserver. */
    data class Cancel(override val reason: ErrorReason) : PublishError

    /** The undo failed, so the deck is still published. */
    data class Undo(override val reason: ErrorReason) : PublishError
}

sealed interface PublishDeckEffect {
    data object NavigateBack : PublishDeckEffect

    /** The user chose to sign in again from a session failure; the session is already cleared. */
    data object NavigateToOnboarding : PublishDeckEffect
    data class Published(val deckId: String) : PublishDeckEffect

    /** The announcement post went out, or didn't. Cosmetic either way — the deck is published. */
    data object Shared : PublishDeckEffect
    data object ShareFailed : PublishDeckEffect
}
