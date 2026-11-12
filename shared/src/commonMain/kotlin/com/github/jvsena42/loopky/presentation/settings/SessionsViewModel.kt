package com.github.jvsena42.loopky.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.SignerRepository
import com.github.jvsena42.loopky.domain.model.AuthorizedSession
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.RequestedAccess
import com.github.jvsena42.loopky.domain.model.SignInRefusal
import com.github.jvsena42.loopky.domain.model.SignInRefusedException
import com.github.jvsena42.loopky.domain.model.SignInRequest
import com.github.jvsena42.loopky.util.Log
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

/**
 * Settings → Sessions: the apps this account has approved, and the approval of a new one when a
 * `pubkyauth://` link arrives by deeplink, scan or paste (Loopky as a signer, like Pubky Ring).
 *
 * Nothing here logs a link or an approval's error text: the link carries the relay secret.
 */
class SessionsViewModel(
    private val signerRepository: SignerRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SessionsEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SessionsEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var approveJob: Job? = null

    init {
        load()
    }

    fun onRetry() = load()

    /** A link from a deeplink, the scanner or the clipboard, or text with one in it. */
    fun onSignInLink(text: String) {
        if (approveJob?.isActive == true) return
        val inspected = signerRepository.inspect(text)
        val request = inspected.getOrNull()
        val refusal = (inspected.exceptionOrNull() as? SignInRefusedException)?.reason
            ?: SignInRefusal.NotASignInLink.takeIf { request == null }
        _state.update {
            it.copy(
                approval = ApprovalUiState(
                    request = request,
                    refusal = refusal,
                    phase = if (refusal == null) ApprovalPhase.Reviewing else ApprovalPhase.Refused,
                ),
            )
        }
    }

    fun onApprove() {
        val approval = _state.value.approval ?: return
        val request = approval.request ?: return
        if (approval.phase != ApprovalPhase.Reviewing && approval.phase != ApprovalPhase.Failed) return
        approveJob = viewModelScope.launch {
            _state.update { it.copy(approval = approval.copy(phase = ApprovalPhase.Approving, failure = null)) }
            signerRepository.approve(request)
                .onSuccess {
                    _state.update { it.copy(approval = it.approval?.copy(phase = ApprovalPhase.Approved)) }
                }
                .onFailure { err ->
                    Log.e(TAG, "onApprove: FAILED — ${err::class.simpleName}")
                    val refusal = (err as? SignInRefusedException)?.reason
                    _state.update {
                        it.copy(
                            approval = it.approval?.copy(
                                phase = if (refusal != null) ApprovalPhase.Refused else ApprovalPhase.Failed,
                                refusal = refusal,
                                failure = if (refusal != null) null else err.toErrorReason(),
                            ),
                        )
                    }
                }
        }
    }

    /** Deny, Close or Done. After an approval the list is read again, so the new app shows up. */
    fun onDismissApproval() {
        if (_state.value.approval?.phase == ApprovalPhase.Approving) return
        val approved = _state.value.approval?.phase == ApprovalPhase.Approved
        _state.update { it.copy(approval = null) }
        if (approved) load()
    }

    fun onRevoke(grantId: String) {
        val target = _state.value.sessions.firstOrNull { it.grantId == grantId } ?: return
        if (target.isThisDevice || grantId in _state.value.revoking) return
        viewModelScope.launch {
            _state.update { it.copy(revoking = it.revoking + grantId) }
            signerRepository.revoke(grantId)
                .onSuccess {
                    _state.update { current ->
                        current.copy(
                            sessions = current.sessions.filterNot { it.grantId == grantId },
                            revoking = current.revoking - grantId,
                        )
                    }
                }
                .onFailure { err ->
                    Log.e(TAG, "onRevoke: FAILED — ${err.message}", err)
                    _state.update { it.copy(revoking = it.revoking - grantId) }
                    _effects.emit(SessionsEffect.RevokeFailed(err.toErrorReason()))
                }
        }
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val canApprove = signerRepository.canApprove.first()
            _state.update { it.copy(canApprove = canApprove) }
            if (!canApprove) {
                _state.update { it.copy(isLoading = false, sessions = emptyList(), errorReason = null) }
                return@launch
            }
            _state.update { it.copy(isLoading = true, errorReason = null) }
            signerRepository.authorizedSessions()
                .onSuccess { sessions -> _state.update { it.copy(isLoading = false, sessions = sessions) } }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err.message}", err)
                    _state.update { it.copy(isLoading = false, errorReason = err.toErrorReason()) }
                }
        }
    }

    companion object {
        private const val TAG = "Loopky/SessionsVM"
    }
}

data class SessionsUiState(
    val isLoading: Boolean = true,
    /** False when Pubky Ring holds the key: there is nothing here to list or approve with. */
    val canApprove: Boolean = true,
    val sessions: List<AuthorizedSession> = emptyList(),
    /** Grants whose revoke is in flight, so a second tap cannot send a second one. */
    val revoking: Set<String> = emptySet(),
    val errorReason: ErrorReason? = null,
    /** The sign-in request on screen, if any. */
    val approval: ApprovalUiState? = null,
) {
    val isEmpty: Boolean get() = canApprove && !isLoading && errorReason == null && sessions.isEmpty()
}

data class ApprovalUiState(
    /** Null when the link was refused before anything could be shown. */
    val request: SignInRequest?,
    val refusal: SignInRefusal? = null,
    val phase: ApprovalPhase = ApprovalPhase.Reviewing,
    val failure: ErrorReason? = null,
) {
    /** Who is asking, or null for a legacy link that does not say. */
    val clientId: String? get() = request?.clientId
    val access: List<RequestedAccess> get() = request?.access.orEmpty()
    val reachesBeyondLoopky: Boolean get() = request?.reachesBeyondLoopky == true
}

/** PascalCase: these cross to Swift lowercased with nothing between the words. */
enum class ApprovalPhase { Reviewing, Refused, Approving, Approved, Failed }

sealed interface SessionsEffect {
    data class RevokeFailed(val reason: ErrorReason) : SessionsEffect
}
