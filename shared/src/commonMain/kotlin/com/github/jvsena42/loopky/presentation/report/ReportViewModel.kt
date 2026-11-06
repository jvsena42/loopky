package com.github.jvsena42.loopky.presentation.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.ReportIssue
import com.github.jvsena42.loopky.domain.model.ReportLimits
import com.github.jvsena42.loopky.domain.model.ReportSubject
import com.github.jvsena42.loopky.util.Log
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
 * The report sheet, for a profile ([deck] null) or a deck. Sending also blocks the account — the
 * sheet says so before the reader sends — and [ReportEffect.Sent] tells the screen underneath to
 * close, as a block does (#577). Only offered to a signed-in reader; the screens raise the
 * sign-in prompt for a guest instead of this sheet.
 */
class ReportViewModel(
    private val accountPubky: String,
    private val deck: Deck?,
    private val discoveryRepository: DiscoveryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ReportUiState(isDeck = deck != null))
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ReportEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<ReportEffect> = _effects.asSharedFlow()

    private var sendJob: Job? = null

    fun onIssueSelected(issue: ReportIssue) {
        _state.update { it.copy(issue = issue, errorReason = null) }
    }

    fun onDetailsChange(details: String) {
        _state.update { it.copy(details = details.take(ReportLimits.DETAILS_MAX_LENGTH)) }
    }

    fun onSend() {
        val issue = _state.value.issue ?: return
        if (sendJob?.isActive == true) return
        sendJob = viewModelScope.launch {
            _state.update { it.copy(isSending = true, errorReason = null) }
            val subject = deck?.let { ReportSubject.OfDeck(it) } ?: ReportSubject.Profile(accountPubky)
            discoveryRepository.report(subject, issue, _state.value.details)
                .onSuccess { outcome ->
                    _state.update { it.copy(isSending = false) }
                    _effects.emit(ReportEffect.Sent(blocked = outcome.blocked))
                }
                .onFailure { err ->
                    Log.w(TAG, "onSend: FAILED — ${err.message}")
                    _state.update { it.copy(isSending = false, errorReason = err.toErrorReason()) }
                }
        }
    }

    private companion object {
        const val TAG = "Loopky/ReportVM"
    }
}

data class ReportUiState(
    /** Words differ for a deck: the sheet says the deck's *author* will be blocked. */
    val isDeck: Boolean,
    val issue: ReportIssue? = null,
    val details: String = "",
    val isSending: Boolean = false,
    /** The report did not arrive. Nothing was tagged or blocked; the reader can send again. */
    val errorReason: ErrorReason? = null,
) {
    /** In the order pubky.app lists them, for the picker on both platforms. */
    val issues: List<ReportIssue> get() = ReportIssue.entries

    val canSend: Boolean get() = issue != null && !isSending

    val detailsMaxLength: Int get() = ReportLimits.DETAILS_MAX_LENGTH
}

sealed interface ReportEffect {
    /**
     * The report reached the moderators. [blocked] false means the block after it failed: the
     * account is still visible, and the screen must say so rather than close as if it were gone.
     */
    data class Sent(val blocked: Boolean) : ReportEffect
}
