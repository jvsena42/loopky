package com.github.jvsena42.loopky.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.data.unsplash.UnsplashError
import com.github.jvsena42.loopky.data.unsplash.UnsplashException
import com.github.jvsena42.loopky.data.unsplash.maskedKeySuffix
import com.github.jvsena42.loopky.domain.model.AppTheme
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.domain.model.UnbackedUpLocalKey
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// LongParameterList: Settings is a screen of unrelated concerns by nature — identity, sharing,
// studying, an API key — and each one needs its own collaborator.
// TooManyFunctions for the same reason: one handler per control, and Settings has a lot of
// controls. Splitting it would mean two ViewModels over one screen's state.
@Suppress("LongParameterList", "TooManyFunctions")
class SettingsViewModel(
    private val identityRepository: IdentityRepository,
    private val pubkyClient: PubkyClient,
    private val appPreferences: AppPreferences,
    private val unsplashKeyStore: UnsplashKeyStore,
    private val unsplashClient: UnsplashClient,
    private val settingsRepository: SettingsRepository,
    appVersion: String = "",
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(appVersion = appVersion))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var signOutJob: Job? = null
    private var unsplashKeyJob: Job? = null
    private var studySettingsJob: Job? = null
    private var countdownJob: Job? = null
    private var deleteJob: Job? = null

    init {
        load()
        observeKeyCustody()
        // Collected rather than read once: the publish/follow/clone prompts carry their own
        // "Don't ask again", and flipping it there has to move this switch too — they are one
        // setting with two controls, not two settings.
        appPreferences.shareOnPubky
            .onEach { enabled -> _state.update { it.copy(shareOnPubky = enabled) } }
            .launchIn(viewModelScope)
        // Collected for the same reason: saving and removing the key both go through the store,
        // and the status row has to follow it rather than a local copy.
        unsplashKeyStore.key
            .onEach { key -> _state.update { it.copy(unsplashKeyStatus = statusFor(key)) } }
            .launchIn(viewModelScope)
        // Collected rather than read once because the app's root collects the same flow: the two
        // controls are one setting, and a picker showing a stale value after the screen repainted
        // around it is the obvious failure.
        appPreferences.themeMode
            .onEach { theme -> _state.update { it.copy(themeMode = theme) } }
            .launchIn(viewModelScope)
        // Collected, not read once: the record loads asynchronously, and the rows stay disabled
        // until it has — writing before then would put defaults over the user's real settings.
        settingsRepository.studySettings
            .onEach { snapshot ->
                _state.update {
                    it.copy(studySettings = snapshot.settings, canEditStudySettings = snapshot.isEditable)
                }
            }
            .launchIn(viewModelScope)
        viewModelScope.launch { settingsRepository.ensureLoaded() }
    }

    /**
     * Note what this never does: read [UnsplashClient]'s build-time fallback. The user is told a
     * shared key is in use, never what it is. Their own key is reduced to four characters before
     * it can reach [SettingsUiState].
     */
    private fun statusFor(userKey: String): UnsplashKeyStatus = when {
        userKey.isNotBlank() -> UnsplashKeyStatus.UserSet(maskedKeySuffix(userKey))
        unsplashClient.hasFallbackKey -> UnsplashKeyStatus.UsingBuiltIn
        else -> UnsplashKeyStatus.NotSet
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: fetching session")
            _state.update { it.copy(isLoading = true) }

            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()

            // Pubky Ring's session payload carries no `homeserver` field, so this was always
            // blank and Settings permanently showed "Unknown". Resolve it from the pkarr record.
            val homeserver = session?.homeserver?.takeIf { it.isNotBlank() }
                ?: session?.identity?.pubky?.let { pubky ->
                    runSuspendCatching { pubkyClient.getHomeserver(pubky).getOrNull() }.getOrNull()
                }

            _state.update {
                it.copy(
                    isLoading = false,
                    pubky = session?.identity?.pubky.orEmpty(),
                    displayName = session?.identity?.displayName,
                    homeserver = homeserver.orEmpty(),
                )
            }
            Log.d(TAG, "load: done — hasSession=${session != null}")
        }
    }

    fun onCopyPubkyClick() {
        val pubky = _state.value.pubky
        if (pubky.isNotBlank()) {
            viewModelScope.launch { _effects.emit(SettingsEffect.CopyToClipboard(pubky)) }
        }
    }

    /** The homeserver is a key too, and it is the one thing support will ask for. */
    fun onCopyHomeserverClick() {
        val homeserver = _state.value.homeserver
        if (homeserver.isNotBlank()) {
            viewModelScope.launch { _effects.emit(SettingsEffect.CopyToClipboard(homeserver)) }
        }
    }

    /**
     * Announcing, not visibility. The deck is public either way — see
     * [com.github.jvsena42.loopky.domain.model.DeckAnnouncement].
     */
    fun onShareOnPubkyChange(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setShareOnPubky(enabled) }
    }

    /**
     * The chosen palette. Device-local, so nothing is published and nothing can fail — the write
     * is to `NSUserDefaults`/`SharedPreferences` and the repaint follows the same flow the app's
     * root is already collecting.
     */
    fun onThemeModeChange(theme: AppTheme) {
        viewModelScope.launch { appPreferences.setThemeMode(theme) }
    }

    fun onNewCardsGoalChange(goal: Int) =
        updateStudySettings { it.copy(newCardsPerDayGoal = goal) }

    fun onIntervalChange(grade: SrsGrade, days: Int) = updateStudySettings {
        when (grade) {
            SrsGrade.Hard -> it.copy(hardDays = days)
            SrsGrade.Good -> it.copy(goodDays = days)
            SrsGrade.Easy -> it.copy(easyDays = days)
            // Again is a fixed ten-minute relearn step, not an interval the user picks.
            SrsGrade.Again -> it
        }
    }

    /**
     * Writes the whole settings object, since the record is written whole anyway.
     *
     * Optimistic on the UI, authoritative on the repository: the row shows the new number at once,
     * and a rejected write (the record was never read) rolls it back to whatever the repository
     * still holds rather than leaving a value on screen that was never saved.
     */
    private fun updateStudySettings(edit: (StudySettings) -> StudySettings) {
        studySettingsJob?.cancel()
        studySettingsJob = viewModelScope.launch {
            val next = edit(_state.value.studySettings).sanitized()
            _state.update { it.copy(studySettings = next) }
            settingsRepository.update(next)
                .onFailure { err ->
                    Log.e(TAG, "updateStudySettings: FAILED — ${err.message}", err)
                    _state.update {
                        it.copy(studySettings = settingsRepository.studySettings.value.settings)
                    }
                    _effects.emit(SettingsEffect.ShowError(SettingsErrorMessage.StudySettingsNotSaved))
                }
        }
    }

    /**
     * Verifies [key] against Unsplash before storing it, so a typo is rejected here rather than
     * days later as an unexplained empty grid.
     *
     * A network failure is not the key's fault, so an unreachable Unsplash still saves — refusing
     * would make the field unusable offline. Only an outright rejection blocks the save.
     *
     * [key] arrives as an argument and is never held in [SettingsUiState]: a draft secret in a
     * StateFlow is one state dump away from a log.
     */
    fun onSaveUnsplashKey(key: String) {
        val candidate = key.trim()
        if (candidate.isBlank() || unsplashKeyJob?.isActive == true) return
        unsplashKeyJob = viewModelScope.launch {
            _state.update { it.copy(isVerifyingUnsplashKey = true, unsplashKeyError = null) }
            val verdict = unsplashClient.verify(candidate)
            val error = verdict.exceptionOrNull()?.toUnsplashError()
            if (error == UnsplashError.InvalidKey || error == UnsplashError.MissingKey) {
                Log.d(TAG, "onSaveUnsplashKey: rejected — $error")
                _state.update { it.copy(isVerifyingUnsplashKey = false, unsplashKeyError = error) }
                return@launch
            }
            unsplashKeyStore.save(candidate)
            Log.d(TAG, "onSaveUnsplashKey: stored a user key")
            _state.update { it.copy(isVerifyingUnsplashKey = false, unsplashKeyError = null) }
        }
    }

    /** Drops the user's key; the build-time fallback, if any, takes over again. */
    fun onRemoveUnsplashKey() {
        if (unsplashKeyJob?.isActive == true) return
        unsplashKeyJob = viewModelScope.launch {
            unsplashKeyStore.clear()
            _state.update { it.copy(unsplashKeyError = null) }
        }
    }

    /** Dismisses the inline rejection when the user starts editing again. */
    fun onUnsplashKeyErrorDismissed() {
        _state.update { it.copy(unsplashKeyError = null) }
    }

    private fun Throwable.toUnsplashError(): UnsplashError =
        (this as? UnsplashException)?.error ?: UnsplashError.Unavailable

    /** Custody drives the backup nag, so it is collected for the life of the screen. */
    private fun observeKeyCustody() {
        viewModelScope.launch {
            identityRepository.keyCustody.collect { custody ->
                _state.update { it.copy(keyCustody = custody) }
            }
        }
    }

    fun onSignOutClick() = signOut(force = false)

    /**
     * The user has seen the warning and said yes anyway.
     *
     * Only reachable from the confirm that [UnbackedUpLocalKey] raises, and it destroys the only
     * copy of an identity — so it is a separate entry point rather than a boolean on the first.
     */
    fun onConfirmSignOutWithoutBackup() = signOut(force = true)

    fun onDismissSignOutWarning() = _state.update { it.copy(unbackedUpPubky = null) }

    private fun signOut(force: Boolean) {
        if (signOutJob?.isActive == true) return
        signOutJob = viewModelScope.launch {
            Log.d(TAG, "signOut: force=$force")
            identityRepository.signOut(force = force)
                .onSuccess {
                    _state.update { it.copy(unbackedUpPubky = null) }
                    _effects.emit(SettingsEffect.SignedOut)
                }
                .onFailure { error ->
                    // The repository refuses rather than warns, so this is the only place the
                    // confirm can come from — a UI that forgot to handle it would fail to sign out
                    // rather than silently destroy the key.
                    if (error is UnbackedUpLocalKey) {
                        Log.w(TAG, "signOut: refused — the local key has never been backed up")
                        _state.update { it.copy(unbackedUpPubky = error.pubky) }
                    } else {
                        Log.e(TAG, "signOut: FAILED — ${error::class.simpleName}", error)
                        _effects.emit(SettingsEffect.SignedOut)
                    }
                }
        }
    }

    /**
     * Open the delete-account dialog and start its countdown.
     *
     * The countdown lives here rather than in a `LaunchedEffect` so it survives a rotation — a
     * timer that restarted every time the screen was rebuilt would be a nuisance rather than a
     * safeguard — and so it can be driven on virtual time in a test.
     */
    fun onDeleteAccountClick() {
        if (_state.value.deletion != null) return
        _state.update { it.copy(deletion = DeletionState.Confirming(COUNTDOWN_SECONDS)) }
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            repeat(COUNTDOWN_SECONDS) {
                delay(COUNTDOWN_TICK_MS)
                _state.update { state ->
                    val confirming = state.deletion as? DeletionState.Confirming ?: return@update state
                    state.copy(deletion = confirming.copy(secondsRemaining = confirming.secondsRemaining - 1))
                }
            }
        }
    }

    /** Dismiss before the deed. Deliberately does nothing once [onConfirmDeleteAccount] has run. */
    fun onDeleteAccountDismissed() {
        if (_state.value.deletion !is DeletionState.Confirming) return
        countdownJob?.cancel()
        _state.update { it.copy(deletion = null) }
    }

    fun onConfirmDeleteAccount() {
        val confirming = _state.value.deletion as? DeletionState.Confirming ?: return
        if (!confirming.isConfirmable) return
        if (deleteJob?.isActive == true) return

        countdownJob?.cancel()
        _state.update { it.copy(deletion = DeletionState.Deleting(done = 0, total = 0)) }
        deleteJob = viewModelScope.launch {
            Log.d(TAG, "onConfirmDeleteAccount: sweeping")
            identityRepository.deleteAccount { done, total ->
                _state.update { it.copy(deletion = DeletionState.Deleting(done = done, total = total)) }
            }
                .onSuccess {
                    Log.d(TAG, "onConfirmDeleteAccount: done")
                    _state.update { it.copy(deletion = null) }
                    _effects.emit(SettingsEffect.SignedOut)
                }
                .onFailure { err ->
                    // Back to the confirmation, already confirmable: the user has read the warning
                    // once and a second ten-second wait would only punish them for a homeserver
                    // that was unreachable. They are still signed in, which is the point.
                    Log.e(TAG, "onConfirmDeleteAccount: FAILED — ${err.message}", err)
                    _state.update { it.copy(deletion = DeletionState.Confirming(secondsRemaining = 0)) }
                    _effects.emit(SettingsEffect.ShowError(SettingsErrorMessage.AccountNotDeleted))
                }
        }
    }

    companion object {
        private const val TAG = "Loopky/SettingsVM"

        /**
         * How long the confirm button stays dead.
         *
         * Long enough to be read rather than tapped through. This is the one action in Loopky that
         * destroys work irrecoverably, so the delay is the feature.
         */
        const val COUNTDOWN_SECONDS = 10

        private const val COUNTDOWN_TICK_MS = 1_000L
    }
}

data class SettingsUiState(
    val isLoading: Boolean = true,
    /**
     * Set when sign-out was refused because Loopky holds a key nobody has backed up. The screen
     * raises a confirm; only [SettingsViewModel.onConfirmSignOutWithoutBackup] proceeds.
     */
    val unbackedUpPubky: String? = null,
    /**
     * What key this *device* holds — not necessarily the signed-in account's. Read it through
     * [holdsOwnKey] rather than directly: an abandoned local signup leaves a key behind for a
     * pubky nobody is signed into.
     */
    val keyCustody: KeyCustody = KeyCustody.External,
    val pubky: String = "",
    val displayName: String? = null,
    val homeserver: String = "",
    val appVersion: String = "",
    /** Whether Loopky may offer to announce a deck on Pubky. Default on; off means never asked. */
    val shareOnPubky: Boolean = true,
    /** The palette the app paints itself in. Device-local — see [AppTheme]. */
    val themeMode: AppTheme = AppTheme.System,
    val unsplashKeyStatus: UnsplashKeyStatus = UnsplashKeyStatus.NotSet,
    val isVerifyingUnsplashKey: Boolean = false,
    val unsplashKeyError: UnsplashError? = null,
    /** The user's own scheduling settings. Defaults until the record has been read. */
    val studySettings: StudySettings = StudySettings.Default,
    /**
     * Whether the study rows accept input.
     *
     * False until the record has actually been read, because a write from that state would put
     * defaults over whatever the user really had. The repository refuses it too — this is the half
     * that explains the refusal rather than letting a tap silently do nothing.
     */
    val canEditStudySettings: Boolean = false,
    /** Null when the delete-account dialog is closed, which is almost always. */
    val deletion: DeletionState? = null,
) {
    /**
     * Whether this device holds the key for **the account on screen**.
     *
     * The pubky comparison is the whole point. [keyCustody] answers "what key is in the vault",
     * which is not the same question: `createLocalAccount` stores a minted key before `signUp`,
     * an interrupted registration deliberately leaves it there so the signup can be resumed, and
     * signing in with Pubky Ring afterwards never touches the key store. Without the comparison
     * that stranded key offers a Ring user a backup door into an identity that is not theirs.
     */
    val holdsOwnKey: Boolean
        get() = (keyCustody as? KeyCustody.Loopky)?.pubky == pubky && pubky.isNotBlank()
}

/**
 * Where the delete-account flow has got to.
 *
 * Two states, not a pair of booleans, because the second one is not dismissable and the first one
 * is: modelling them separately is what stops a back gesture mid-sweep from closing a dialog over
 * a deletion that is still running.
 */
sealed interface DeletionState {
    /**
     * The warning is on screen. [secondsRemaining] counts down to zero, and the confirm button is
     * dead until it gets there.
     */
    data class Confirming(val secondsRemaining: Int) : DeletionState {
        val isConfirmable: Boolean get() = secondsRemaining <= 0
    }

    /**
     * The sweep is running and there is no way back. [total] is what was known when it started, so
     * treat it as advisory: it can be zero before the first report arrives, and the sweep is
     * allowed to find more work than it predicted.
     */
    data class Deleting(val done: Int, val total: Int) : DeletionState
}

/**
 * What the Unsplash row reports. Carries no key material: [UserSet] holds four characters, and the
 * built-in fallback is represented by its existence alone.
 */
sealed interface UnsplashKeyStatus {
    /** Neither the user nor the build supplies a key — web image search is off. */
    data object NotSet : UnsplashKeyStatus

    /** Running on the shared build-time key, whose rate limit every install competes for. */
    data object UsingBuiltIn : UnsplashKeyStatus

    /** The user's own key. [suffix] is its last four characters and never more. */
    data class UserSet(val suffix: String) : UnsplashKeyStatus
}

sealed interface SettingsEffect {
    data object SignedOut : SettingsEffect
    data class CopyToClipboard(val text: String) : SettingsEffect

    /** Carries a case, not a sentence — the words belong to the platform layer. */
    data class ShowError(val message: SettingsErrorMessage) : SettingsEffect
}

enum class SettingsErrorMessage { StudySettingsNotSaved, AccountNotDeleted }
