package com.github.jvsena42.loopky.presentation.recap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.PubkyLinks
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.RecapShareTarget
import com.github.jvsena42.loopky.domain.model.YearRecap
import com.github.jvsena42.loopky.domain.model.maturityThresholdDays
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecapViewModel(
    private val year: Int,
    private val identityRepository: IdentityRepository,
    private val deckRepository: DeckRepository,
    private val srsRepository: SrsRepository,
    private val settingsRepository: SettingsRepository,
    private val discoveryRepository: DiscoveryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<RecapUiState>(RecapUiState.Loading)
    val state: StateFlow<RecapUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<RecapEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<RecapEffect> = _effects.asSharedFlow()

    init {
        load()
    }

    fun onRetry() = load()

    private fun load() {
        _state.update { RecapUiState.Loading }
        viewModelScope.launch {
            runSuspendCatching {
                val session = identityRepository.currentSession()
                    ?: requireNotNull(identityRepository.loadPersistedSession()) { "Not signed in" }
                val owned = deckRepository.listOwned()
                // Someone else's homeserver being down must not cost the user their own year.
                val followed = runSuspendCatching { deckRepository.listFollowed() }.getOrDefault(emptyList())
                val decks = (owned + followed).distinctBy { it.id }
                // Review state is loaded per deck as a side effect of counting it; statesForDeck
                // below only reads what this leaves in memory.
                srsRepository.countsToday(decks)
                settingsRepository.ensureLoaded()
                val recap = YearRecap.from(
                    year = year,
                    owned = owned,
                    followed = followed,
                    statesByDeck = decks.associate { it.id to srsRepository.statesForDeck(it.id).values },
                    thresholdDays = settingsRepository.studySettings.value.settings.maturityThresholdDays,
                )
                session.identity to recap
            }.onSuccess { (identity, recap) ->
                _state.update {
                    if (recap.isEmpty) {
                        RecapUiState.Empty(year)
                    } else {
                        RecapUiState.Content(
                            recap = recap,
                            identity = identity,
                            webUrl = PubkyLinks.recapWebUrl(identity.pubky, year),
                        )
                    }
                }
            }.onFailure { err ->
                Log.e(TAG, "load: FAILED — ${err.message}", err)
                _state.update { RecapUiState.Error(err.toErrorReason()) }
            }
        }
    }

    /**
     * [text] is the platform's localized caption for [target]; the words are its, the link and the
     * length limit are not. Pubky is the one target written here rather than handed to another
     * app, so it raises a preview instead of an effect.
     */
    fun onShare(target: RecapShareTarget, text: String) {
        val content = _state.value as? RecapUiState.Content ?: return
        val post = target.compose(text, content.webUrl)
        if (target == RecapShareTarget.Pubky) {
            _state.update { content.copy(pubkyPrompt = RecapPostPrompt(preview = post)) }
        } else {
            _effects.tryEmit(RecapEffect.Share(target, content.recap, text = post, url = content.webUrl))
        }
    }

    fun onPostConfirm() {
        val content = _state.value as? RecapUiState.Content ?: return
        val prompt = content.pubkyPrompt?.takeUnless { it.isPosting } ?: return
        _state.update { content.copy(pubkyPrompt = prompt.copy(isPosting = true)) }
        viewModelScope.launch {
            discoveryRepository.postRecap(prompt.preview, content.webUrl)
                .onSuccess { _effects.emit(RecapEffect.Posted) }
                .onFailure { _effects.emit(RecapEffect.PostFailed(it.toErrorReason())) }
            dismissPrompt()
        }
    }

    fun onPostDismiss() {
        if ((_state.value as? RecapUiState.Content)?.pubkyPrompt?.isPosting == true) return
        dismissPrompt()
    }

    private fun dismissPrompt() {
        _state.update { current -> (current as? RecapUiState.Content)?.copy(pubkyPrompt = null) ?: current }
    }

    companion object {
        private const val TAG = "Loopky/RecapVM"
    }
}

sealed interface RecapUiState {
    data object Loading : RecapUiState

    /** Nothing studied and nothing created in [year]. */
    data class Empty(val year: Int) : RecapUiState
    data class Content(
        val recap: YearRecap,
        val identity: PubkyIdentity,
        val webUrl: String,
        val pubkyPrompt: RecapPostPrompt? = null,
    ) : RecapUiState
    data class Error(val reason: ErrorReason) : RecapUiState
}

/** [preview] is the post exactly as it will be written. */
data class RecapPostPrompt(val preview: String, val isPosting: Boolean = false)

sealed interface RecapEffect {
    /** Hand [text] (already carrying [url]) and the image for [target] to that app. */
    data class Share(
        val target: RecapShareTarget,
        val recap: YearRecap,
        val text: String,
        val url: String,
    ) : RecapEffect
    data object Posted : RecapEffect
    data class PostFailed(val reason: ErrorReason) : RecapEffect
}
