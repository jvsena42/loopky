package com.github.jvsena42.loopky.presentation.request

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.domain.model.DeckRequests
import com.github.jvsena42.loopky.domain.model.ErrorReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "Request a deck" sheet, raised from wherever a reader runs out of decks: an empty Home, a
 * search with no matches, the end of Discover. One ViewModel behind every entry point, so each of
 * them only decides what to prefill.
 *
 * Signing in is the host's question, not this one's — a guest is stopped before the sheet opens,
 * and the repository refuses without a session regardless.
 */
class DeckRequestViewModel(
    private val discoveryRepository: DiscoveryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DeckRequestUiState())
    val state: StateFlow<DeckRequestUiState> = _state.asStateFlow()

    /**
     * A fresh sheet seeded with [prefill] — the search query or topic that came up empty. The
     * ViewModel outlives a dismissed sheet, so every opening starts here; one still posting is left
     * alone, or its result would land on a sheet that no longer asked.
     */
    fun start(prefill: String) {
        if (_state.value.isPosting) return
        _state.update { DeckRequestUiState(text = prefill.trim().take(DeckRequests.MAX_LENGTH)) }
    }

    fun onTextChange(text: String) {
        _state.update { if (it.isPosting || it.isSent) it else it.copy(text = text, error = null) }
    }

    /** [lead] is the platform's localized first line; the post is [DeckRequests.compose] of the two. */
    fun onSubmit(lead: String) {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isPosting = true, error = null) }
        viewModelScope.launch {
            discoveryRepository.postDeckRequest(DeckRequests.compose(lead, current.text))
                .onSuccess { _state.update { it.copy(isPosting = false, isSent = true) } }
                .onFailure { err -> _state.update { it.copy(isPosting = false, error = err.toErrorReason()) } }
        }
    }
}

data class DeckRequestUiState(
    val text: String = "",
    val isPosting: Boolean = false,
    /** Posted: the sheet turns into its confirmation and takes no more input. */
    val isSent: Boolean = false,
    val error: ErrorReason? = null,
) {
    val length: Int get() = text.trim().length
    val canSubmit: Boolean get() = !isPosting && !isSent && DeckRequests.isValid(text)
}
