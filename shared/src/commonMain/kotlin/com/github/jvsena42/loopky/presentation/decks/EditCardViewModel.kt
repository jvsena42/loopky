package com.github.jvsena42.loopky.presentation.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.FormError
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.remoteImageRef
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.generateId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The single-card editor.
 *
 * [providedCardId] blank means "a card that does not exist yet" — how the deck editor adds a card
 * now that its own list is a page rather than the whole deck (#52). The card is written by the
 * same [DeckRepository.upsertCard] either way; creating one just appends instead of replacing.
 */
@Suppress("TooManyFunctions")
class EditCardViewModel(
    private val deckId: String,
    providedCardId: String,
    private val cardRepository: CardRepository,
    private val deckRepository: DeckRepository,
    private val mediaRepository: MediaRepository,
) : ViewModel() {
    private val isNewCard = providedCardId.isBlank()
    private val cardId = providedCardId.ifBlank { generateId() }

    private val _state = MutableStateFlow(EditCardUiState())
    val state: StateFlow<EditCardUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<EditCardEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<EditCardEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null

    init {
        load()
    }

    private fun load() {
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: deckId=$deckId cardId=$cardId isNew=$isNewCard")
            val deck = deckRepository.getLocal(deckId)
            // Never looked up for a new card: a miss makes `get` walk every chunk in the deck
            // looking for an id nothing has, which is ~200 requests on a 20k-card deck.
            val card = if (isNewCard) null else cardRepository.get(deckId, cardId)
            if (card == null && !isNewCard) {
                _state.update { it.copy(error = "Card not found.") }
                return@launch
            }
            val deckCards = deck?.cardCount ?: 0
            // Position by study order among the cards loaded for this deck; the manifest no
            // longer carries a card index to look it up in. A new card is the deck's next one.
            val cardIndex = if (isNewCard) deckCards + 1 else studyPosition()
            _state.update { loadedState(deck, card, cardIndex, deckCards) }
        }
    }

    private suspend fun studyPosition(): Int =
        cardRepository.listByDeck(deckId)
            .indexOfFirst { it.id == cardId }
            .let { if (it >= 0) it + 1 else 0 }

    private fun loadedState(deck: Deck?, card: Card?, cardIndex: Int, deckCards: Int) = EditCardUiState(
        deckId = deckId,
        // The deck's author, not the signed-in user: a blob on a followed deck lives under
        // *their* pubky, and the editor renders its thumbnail from there.
        authorPubky = deck?.authorPubky ?: "",
        deckTitle = deck?.title ?: "",
        isNewCard = isNewCard,
        cardIndex = cardIndex,
        totalCards = if (isNewCard) deckCards + 1 else deckCards,
        frontText = card?.front?.text ?: "",
        backText = card?.back?.text ?: "",
        frontImageRef = card?.front?.imageRef,
        backImageRef = card?.back?.imageRef,
        hasImage = card?.front?.imageRef != null || card?.back?.imageRef != null,
        hasAudio = card?.front?.audioRef != null || card?.back?.audioRef != null,
    )

    /**
     * A web (Unsplash) image was chosen for the card front — saved by URL.
     *
     * A URL no client could render is dropped rather than stored: the sheet cannot normally offer
     * one (its Done button waits on a preview that actually loaded), so this is the guard for
     * every other way a string reaches here.
     */
    fun onFrontImageWebSelected(url: String) {
        val ref = remoteImageRef(url) ?: return
        _state.update { it.copy(frontImageRef = ref, frontPendingBytes = null, frontPendingMime = null, hasImage = true) }
    }

    /** A gallery image was chosen for the card front — already compressed; uploaded on save. */
    fun onFrontImageGallerySelected(bytes: ByteArray, mime: String) {
        _state.update {
            it.copy(frontImageRef = null, frontPendingBytes = bytes, frontPendingMime = mime, hasImage = true)
        }
    }

    fun onRemoveFrontImage() {
        _state.update { it.copy(frontImageRef = null, frontPendingBytes = null, frontPendingMime = null) }
    }

    /** A web (Unsplash) image was chosen for the card back — saved by URL. See the front's note. */
    fun onBackImageWebSelected(url: String) {
        val ref = remoteImageRef(url) ?: return
        _state.update { it.copy(backImageRef = ref, backPendingBytes = null, backPendingMime = null) }
    }

    /** A gallery image was chosen for the card back — already compressed; uploaded on save. */
    fun onBackImageGallerySelected(bytes: ByteArray, mime: String) {
        _state.update { it.copy(backImageRef = null, backPendingBytes = bytes, backPendingMime = mime) }
    }

    fun onRemoveBackImage() {
        _state.update { it.copy(backImageRef = null, backPendingBytes = null, backPendingMime = null) }
    }

    fun onFrontTextChanged(text: String) {
        _state.update { it.copy(frontText = text, frontError = cardTextErrorFor(text)) }
    }

    fun onBackTextChanged(text: String) {
        _state.update { it.copy(backText = text, backError = cardTextErrorFor(text)) }
    }

    fun onSaveClick() {
        if (saveJob?.isActive == true) return
        val s = _state.value
        // Both sides, each on its own. `DeckRepository.upsertCard` refuses a card with an empty
        // side outright, so a front cleared while the back still had text used to reach the
        // repository's `require` and surface its message — which names the card by its internal
        // id — straight to the user.
        val frontError = cardTextErrorFor(s.frontText) ?: sideRequiredErrorFor(s.frontText, s.hasFrontImage)
        val backError = cardTextErrorFor(s.backText) ?: sideRequiredErrorFor(s.backText, s.hasBackImage)
        if (frontError != null || backError != null) {
            _state.update { it.copy(frontError = frontError, backError = backError, error = null) }
            return
        }
        saveJob = viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            Log.d(TAG, "save: cardId=$cardId")

            val existingCard = if (isNewCard) null else cardRepository.get(deckId, cardId)
            val now = epochMillis()
            val frontImage = resolveFrontImage(s)
            val backImage = resolveBackImage(s)
            val card = Card(
                id = cardId,
                deckId = deckId,
                updatedAt = now,
                front = CardSide(
                    text = s.frontText.ifBlank { null },
                    imageRef = frontImage,
                    audioRef = existingCard?.front?.audioRef,
                ),
                back = CardSide(
                    text = s.backText.ifBlank { null },
                    imageRef = backImage,
                    audioRef = existingCard?.back?.audioRef,
                ),
            )

            // Goes through DeckRepository so the chunk write and the manifest's chunk entry move
            // together — writing the card alone used to leave the manifest permanently stale.
            deckRepository.upsertCard(deckId, card)
                .onSuccess {
                    Log.d(TAG, "save: SUCCESS")
                    _state.update { it.copy(isSaving = false) }
                    _effects.emit(EditCardEffect.SaveSuccess)
                }
                .onFailure { err ->
                    Log.e(TAG, "save: FAILED — ${err.message}", err)
                    _state.update { it.copy(isSaving = false, error = err.message ?: "Save failed.") }
                }
        }
    }

    fun onDeleteCard() {
        // Nothing was ever written, so there is nothing to delete — just leave.
        if (isNewCard) {
            viewModelScope.launch { _effects.emit(EditCardEffect.NavigateBack) }
            return
        }
        viewModelScope.launch {
            Log.d(TAG, "delete: cardId=$cardId")
            deckRepository.deleteCard(deckId, cardId)
                .onSuccess { _effects.emit(EditCardEffect.Deleted) }
                .onFailure { err ->
                    _state.update { it.copy(error = err.message ?: "Delete failed.") }
                }
        }
    }

    fun onCancelClick() {
        viewModelScope.launch { _effects.emit(EditCardEffect.NavigateBack) }
    }

    /** Upload a pending gallery image, or keep the chosen web/existing ref. */
    private suspend fun resolveFrontImage(s: EditCardUiState): MediaRef.Image? = when {
        s.frontPendingBytes != null ->
            mediaRepository.putImage(deckId, s.frontPendingBytes, s.frontPendingMime ?: "image/jpeg")
                .onFailure { Log.e(TAG, "front image upload failed — ${it.message}", it) }
                .getOrNull()

        else -> s.frontImageRef
    }

    /** Upload a pending gallery image, or keep the chosen web/existing ref (card back). */
    private suspend fun resolveBackImage(s: EditCardUiState): MediaRef.Image? = when {
        s.backPendingBytes != null ->
            mediaRepository.putImage(deckId, s.backPendingBytes, s.backPendingMime ?: "image/jpeg")
                .onFailure { Log.e(TAG, "back image upload failed — ${it.message}", it) }
                .getOrNull()

        else -> s.backImageRef
    }

    private fun cardTextErrorFor(text: String): FormError? =
        if (text.length > CARD_TEXT_MAX_LENGTH) FormError.CardTextTooLong else null

    /** A side is what the user can see: its own words, or its own picture. One of the two. */
    private fun sideRequiredErrorFor(text: String, hasImage: Boolean): FormError? =
        if (text.isBlank() && !hasImage) FormError.CardSideRequired else null

    companion object {
        private const val TAG = "Loopky/EditCardVM"
        private const val CARD_TEXT_MAX_LENGTH = 2000
    }
}

data class EditCardUiState(
    val deckId: String = "",
    val authorPubky: String = "",
    val deckTitle: String = "",
    /** True while editing a card that has not been written yet — hides Delete, titles the screen. */
    val isNewCard: Boolean = false,
    val cardIndex: Int = 0,
    val totalCards: Int = 0,
    val frontText: String = "",
    val backText: String = "",
    val frontImageRef: MediaRef.Image? = null,
    val frontPendingBytes: ByteArray? = null,
    val frontPendingMime: String? = null,
    val backImageRef: MediaRef.Image? = null,
    val backPendingBytes: ByteArray? = null,
    val backPendingMime: String? = null,
    val hasImage: Boolean = false,
    val hasAudio: Boolean = false,
    val isSaving: Boolean = false,
    val frontError: FormError? = null,
    val backError: FormError? = null,
    /** A failure from the homeserver, not from the form. Field problems are [FormError]s. */
    val error: String? = null,
) {
    /** A picture already on this side, or one picked and not yet uploaded — both count as content. */
    val hasFrontImage: Boolean get() = frontImageRef != null || frontPendingBytes != null
    val hasBackImage: Boolean get() = backImageRef != null || backPendingBytes != null
}

sealed interface EditCardEffect {
    data object NavigateBack : EditCardEffect
    data object SaveSuccess : EditCardEffect
    data object Deleted : EditCardEffect
}
