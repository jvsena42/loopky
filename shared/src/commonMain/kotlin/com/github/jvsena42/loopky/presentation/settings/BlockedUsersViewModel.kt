package com.github.jvsena42.loopky.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
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
 * The accounts the signed-in user has blocked, reached from Settings, each with an Unblock — the
 * only place one is offered. A row does not open the profile: a blocked account is not shown.
 *
 * The list is pubky.app's mute list, so it also shows people muted on pubky.app — the screen's
 * caption says so, or a name the reader never blocked in Loopky reads as a bug (#577).
 */
class BlockedUsersViewModel(
    private val discoveryRepository: DiscoveryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BlockedUsersUiState())
    val state: StateFlow<BlockedUsersUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<BlockedUsersEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<BlockedUsersEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun onRetry() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorReason = null) }
            runSuspendCatching { discoveryRepository.blockedProfiles() }
                .onSuccess { people -> _state.update { it.copy(isLoading = false, people = people) } }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err.message}", err)
                    _state.update { it.copy(isLoading = false, errorReason = err.toErrorReason()) }
                }
        }
    }

    fun onUnblock(pubky: String) {
        if (pubky in _state.value.unblocking) return
        viewModelScope.launch {
            _state.update { it.copy(unblocking = it.unblocking + pubky) }
            discoveryRepository.unblockUser(pubky)
                .onSuccess {
                    _state.update { current ->
                        current.copy(
                            people = current.people.filterNot { it.pubky == pubky },
                            unblocking = current.unblocking - pubky,
                        )
                    }
                }
                .onFailure { err ->
                    Log.e(TAG, "onUnblock: FAILED — ${err.message}", err)
                    _state.update { it.copy(unblocking = it.unblocking - pubky) }
                    _effects.emit(BlockedUsersEffect.ShowError(err.toErrorReason()))
                }
        }
    }

    companion object {
        private const val TAG = "Loopky/BlockedUsersVM"
    }
}

data class BlockedUsersUiState(
    val isLoading: Boolean = true,
    val people: List<PubkyIdentity> = emptyList(),
    /** Pubkys whose Unblock is in flight, so a second tap cannot send a second delete. */
    val unblocking: Set<String> = emptySet(),
    val errorReason: ErrorReason? = null,
) {
    val isEmpty: Boolean get() = !isLoading && errorReason == null && people.isEmpty()
}

sealed interface BlockedUsersEffect {
    data class ShowError(val reason: ErrorReason) : BlockedUsersEffect
}
