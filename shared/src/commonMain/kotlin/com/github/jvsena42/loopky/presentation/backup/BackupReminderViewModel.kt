package com.github.jvsena42.loopky.presentation.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.localDayIndex
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The once-a-day sheet warning that an un-backed-up local key dies with the app.
 *
 * Hosted by the signed-in shell rather than one tab, so it reaches the reader wherever they land.
 * Shown at most once per local day — the day is recorded as it appears, so a dismissal and a
 * killed app count the same — and only for the key of the account actually signed in, for the
 * reason [com.github.jvsena42.loopky.presentation.profile.ProfileUiState.needsBackup] gives.
 */
class BackupReminderViewModel(
    private val identityRepository: IdentityRepository,
    private val appPreferences: AppPreferences,
    private val today: () -> Int = { localDayIndex(epochMillis()) },
) : ViewModel() {
    private val _state = MutableStateFlow(BackupReminderUiState())
    val state: StateFlow<BackupReminderUiState> = _state.asStateFlow()

    private var custody: KeyCustody = KeyCustody.External

    // A custody emission and a foreground check can race, and both would record the day and show.
    private val checkLock = Mutex()

    init {
        viewModelScope.launch {
            identityRepository.keyCustody.collect { latest ->
                custody = latest
                if ((latest as? KeyCustody.Loopky)?.isBackedUp == false) {
                    showIfDue()
                } else {
                    _state.update { it.copy(isVisible = false) }
                }
            }
        }
    }

    /** The app came back to the foreground — a process that outlives midnight still asks once. */
    fun onAppForeground() {
        viewModelScope.launch { showIfDue() }
    }

    /** Both "Back up now" and "Not now" end here; the platform owns the navigation. */
    fun onDismiss() {
        _state.update { it.copy(isVisible = false) }
    }

    private suspend fun showIfDue() {
        checkLock.withLock {
            val local = custody as? KeyCustody.Loopky ?: return
            if (local.isBackedUp || _state.value.isVisible) return
            val day = today()
            if (appPreferences.backupReminderDay.first() == day) return
            val session = runSuspendCatching {
                identityRepository.currentSession() ?: identityRepository.loadPersistedSession()
            }.getOrNull()
            if (session?.identity?.pubky != local.pubky) return
            appPreferences.setBackupReminderDay(day)
            _state.update { it.copy(isVisible = true) }
        }
    }
}

data class BackupReminderUiState(val isVisible: Boolean = false)
