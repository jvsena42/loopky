package com.github.jvsena42.loopky.presentation.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.KeyBackupRepository
import com.github.jvsena42.loopky.data.repository.PhraseQuiz
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.PassphraseStrength
import com.github.jvsena42.loopky.domain.model.strengthOf
import com.github.jvsena42.loopky.platform.PasswordManagerPresence
import com.github.jvsena42.loopky.platform.PubkyRingPresence
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

private const val TAG = "Loopky/BackupVM"

/**
 * The backup menu: which methods exist, and which are done.
 *
 * Skippable on purpose. A user who has just created an account is the least equipped person in the
 * app to evaluate four key-custody options, and blocking them here is how a flashcards app loses
 * someone before they see a card. The Settings nag is the follow-up.
 */
class BackupStartViewModel(
    private val keyBackup: KeyBackupRepository,
    ringPresence: PubkyRingPresence,
    passwordManager: PasswordManagerPresence,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BackupStartUiState(
            ringInstalled = ringPresence.canImportKey(),
            canSaveToPasswordManager = passwordManager.canSave(),
        ),
    )
    val state: StateFlow<BackupStartUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            keyBackup.custody.collect { custody ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        pubky = (custody as? KeyCustody.Loopky)?.pubky,
                        done = (custody as? KeyCustody.Loopky)?.backedUpBy.orEmpty(),
                        // A file-restored key has no words, so the phrase card must not be offered:
                        // it would open a screen with nothing on it.
                        hasPhrase = (custody as? KeyCustody.Loopky)?.hasPhrase == true,
                    )
                }
            }
        }
    }
}

data class BackupStartUiState(
    val isLoading: Boolean = true,
    val pubky: String? = null,
    val done: Set<BackupMethod> = emptySet(),
    val hasPhrase: Boolean = false,
    val ringInstalled: Boolean = false,
    val canSaveToPasswordManager: Boolean = false,
) {
    val isBackedUp: Boolean get() = done.isNotEmpty()

    /**
     * The password-manager card needs the words to hand over, so it goes wherever the phrase card
     * goes — a key restored from a recovery file has no phrase to save.
     */
    val showPasswordManager: Boolean get() = canSaveToPasswordManager && hasPhrase
}

/**
 * Show the twelve words, then send the user to confirm them. The words are loaded on demand and
 * dropped in [onLeave]; they are never in this state longer than the screen is on top.
 */
class BackupPhraseViewModel(
    private val keyBackup: KeyBackupRepository,
    passwordManager: PasswordManagerPresence,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupPhraseUiState(canSaveToPasswordManager = passwordManager.canSave()))
    val state: StateFlow<BackupPhraseUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<BackupPhraseEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<BackupPhraseEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    init {
        onEnter()
    }

    /**
     * Load the words, unless they are already here. Called on every entry, not only from `init`,
     * because [onLeave] empties a ViewModel that **outlives the screen** — retained across a
     * configuration change and kept on the back stack while the confirm quiz is on top. With
     * loading in `init` alone, rotating the phone left a live ViewModel holding no words and
     * nothing that would ever fetch them: an empty grid with Continue permanently disabled.
     */
    fun onEnter() {
        if (loadJob?.isActive == true || _state.value.words.isNotEmpty()) return
        loadJob = viewModelScope.launch {
            keyBackup.revealRecoveryPhrase()
                .onSuccess { phrase ->
                    _state.update { it.copy(isLoading = false, words = phrase.split(" ").filter(String::isNotBlank)) }
                }
                .onFailure {
                    Log.e(TAG, "phrase: FAILED — ${it::class.simpleName}")
                    _state.update { it.copy(isLoading = false, failed = true) }
                }
        }
    }

    /** Reveal the words that are blurred until asked for — a shoulder-surfing guard, not security. */
    fun onRevealClick() = _state.update { it.copy(revealed = true) }

    /**
     * Hand the phrase to the platform's credential sheet. Offered only after the words are on
     * screen: saving a secret the user has not been shown, from a button beside a blurred grid,
     * asks them to trust a backup of something they never saw.
     */
    fun onSaveToPasswordManagerClick() {
        val words = _state.value.words
        if (words.isEmpty() || _state.value.isSavingToPasswordManager) return
        _state.update { it.copy(isSavingToPasswordManager = true, passwordManagerFailed = false) }
        viewModelScope.launch {
            _effects.emit(BackupPhraseEffect.SaveToPasswordManager(words.joinToString(" ")))
        }
    }

    /**
     * The credential sheet closed. [saved] is false for a cancel *and* for a provider that refused.
     *
     * A save is **not** recorded here. What proves this backup is reading the credential back and
     * finding the right phrase in it ([onPasswordManagerReadBack]) — a sheet that reported success
     * while storing nothing retrievable would otherwise retire the nag on an account that is still
     * one lost phone from gone.
     */
    fun onPasswordManagerSaveResult(saved: Boolean) {
        if (!saved) {
            _state.update { it.copy(isSavingToPasswordManager = false, passwordManagerFailed = true) }
            return
        }
        viewModelScope.launch { _effects.emit(BackupPhraseEffect.ReadBackFromPasswordManager) }
    }

    /**
     * What the credential manager handed back, compared against the real phrase. The comparison is
     * the point: it is the difference between "a sheet appeared" and "this account is recoverable".
     */
    fun onPasswordManagerReadBack(secret: String?) {
        val expected = _state.value.words.joinToString(" ")
        val verified = secret != null && expected.isNotEmpty() && secret.trim() == expected
        if (!verified) {
            _state.update { it.copy(isSavingToPasswordManager = false, passwordManagerFailed = true) }
            return
        }
        viewModelScope.launch {
            keyBackup.markBackedUp(BackupMethod.PasswordManager)
            _state.update {
                it.copy(isSavingToPasswordManager = false, passwordManagerFailed = false, savedToPasswordManager = true)
            }
        }
    }

    fun onLeave() {
        loadJob?.cancel()
        loadJob = null
        _state.update { BackupPhraseUiState(canSaveToPasswordManager = _state.value.canSaveToPasswordManager) }
    }
}

data class BackupPhraseUiState(
    val isLoading: Boolean = true,
    val words: List<String> = emptyList(),
    val revealed: Boolean = false,
    val failed: Boolean = false,
    val canSaveToPasswordManager: Boolean = false,
    val isSavingToPasswordManager: Boolean = false,
    val savedToPasswordManager: Boolean = false,
    val passwordManagerFailed: Boolean = false,
) {
    /** Offered only once the words are visible — see `onSaveToPasswordManagerClick`. */
    val showPasswordManagerSave: Boolean
        get() = canSaveToPasswordManager && revealed && words.isNotEmpty()
}

sealed interface BackupPhraseEffect {
    /**
     * Raise the platform's "save a password" sheet for [secret]. Carries the phrase, so it goes
     * straight to the platform layer and is never logged, cached or held in a field — the same rule
     * as `ringExportUrl`.
     */
    data class SaveToPasswordManager(val secret: String) : BackupPhraseEffect

    /** Read the credential back, so the save can be verified rather than assumed. */
    data object ReadBackFromPasswordManager : BackupPhraseEffect
}

/**
 * The confirm quiz. **Passing this is what marks the phrase backed up, not seeing it.** Someone who
 * tapped through a screen of words has not written them down, and recording that as a backup is how
 * the nag stops for an account that is still one lost phone from gone.
 */
class BackupQuizViewModel(
    private val keyBackup: KeyBackupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupQuizUiState())
    val state: StateFlow<BackupQuizUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<BackupEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<BackupEffect> = _effects.asSharedFlow()

    private var quiz: PhraseQuiz? = null

    init {
        // A phrase already in a password manager gets a different confirm step — see [ConfirmMode].
        viewModelScope.launch {
            keyBackup.custody.collect { custody ->
                val saved = (custody as? KeyCustody.Loopky)?.backedUpBy.orEmpty()
                _state.update {
                    it.copy(
                        mode = if (BackupMethod.PasswordManager in saved) {
                            ConfirmMode.PasswordManager
                        } else {
                            ConfirmMode.Recall
                        },
                    )
                }
            }
        }

        viewModelScope.launch {
            keyBackup.buildPhraseQuiz()
                .onSuccess { built ->
                    quiz = built
                    _state.update {
                        it.copy(
                            isLoading = false,
                            positions = built.questions.map { q -> q.position },
                            options = built.questions.map { q -> q.options },
                        )
                    }
                }
                .onFailure {
                    Log.e(TAG, "quiz: FAILED — ${it::class.simpleName}")
                    _state.update { it.copy(isLoading = false, failed = true) }
                }
        }
    }

    fun onAnswer(questionIndex: Int, word: String) {
        _state.update { it.copy(answers = it.answers + (questionIndex to word), wrong = false) }
    }

    fun onSubmit() {
        val current = quiz ?: return
        val answers = _state.value.answers
        if (answers.size < current.questions.size) return

        val allCorrect = current.questions.withIndex().all { (i, q) -> answers[i] == q.answer }
        if (!allCorrect) {
            // Wrong answers clear rather than lock: this is a check that the words were written
            // down, not a security control, and someone with the phrase in front of them should
            // be able to try again.
            _state.update { it.copy(wrong = true, answers = emptyMap()) }
            return
        }

        viewModelScope.launch {
            keyBackup.markBackedUp(BackupMethod.RecoveryPhrase)
            _state.update { it.copy(isDone = true, wrong = false) }
            _effects.emit(BackupEffect.Done)
        }
    }

    /**
     * Confirm a phrase that lives in a password manager, by reading it back out of one.
     *
     * The recall quiz asks whether the user copied twelve words down correctly. Someone who used a
     * password manager copied nothing, so that question tests nothing they did. What is worth
     * checking here is what would actually lose the account: that the credential is still there and
     * still says the right words.
     */
    fun onCheckSavedClick() {
        if (_state.value.isChecking) return
        _state.update { it.copy(isChecking = true, wrong = false) }
        viewModelScope.launch { _effects.emit(BackupEffect.ReadBackFromPasswordManager) }
    }

    /** What the credential manager returned, compared against the real phrase. */
    fun onPasswordManagerReadBack(secret: String?) {
        viewModelScope.launch {
            val expected = keyBackup.revealRecoveryPhrase().getOrNull()
            if (secret == null || expected.isNullOrBlank() || secret.trim() != expected) {
                _state.update { it.copy(isChecking = false, wrong = true) }
                return@launch
            }
            keyBackup.markBackedUp(BackupMethod.PasswordManager)
            _state.update { it.copy(isChecking = false, wrong = false, isDone = true) }
            _effects.emit(BackupEffect.Done)
        }
    }
}

/**
 * How the confirm step proves the phrase is safe. Two different claims, so two different checks:
 * [Recall] tests that the words were written down, [PasswordManager] that they can be read back out
 * of the manager they were saved to.
 */
enum class ConfirmMode { Recall, PasswordManager }

data class BackupQuizUiState(
    val isLoading: Boolean = true,
    val positions: List<Int> = emptyList(),
    val options: List<List<String>> = emptyList(),
    val answers: Map<Int, String> = emptyMap(),
    val wrong: Boolean = false,
    val isDone: Boolean = false,
    val failed: Boolean = false,
    val mode: ConfirmMode = ConfirmMode.Recall,
    val isChecking: Boolean = false,
) {
    val canSubmit: Boolean get() = positions.isNotEmpty() && answers.size == positions.size

    /** The confirm action on the password-manager path; nothing to answer, so only the check gates it. */
    val canCheckSaved: Boolean get() = !isChecking
}

/** Create an encrypted recovery file and hand it to the platform's save sheet. */
class BackupFileViewModel(
    private val keyBackup: KeyBackupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupFileUiState())
    val state: StateFlow<BackupFileUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<BackupEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<BackupEffect> = _effects.asSharedFlow()

    fun onPassphraseChange(value: String) {
        _state.update { it.copy(passphrase = value, strength = strengthOf(value), failed = false) }
    }

    fun onCreateClick() {
        val passphrase = _state.value.passphrase
        if (passphrase.isEmpty() || _state.value.isCreating) return

        viewModelScope.launch {
            _state.update { it.copy(isCreating = true, failed = false) }
            keyBackup.createRecoveryFile(passphrase)
                .onSuccess { blob ->
                    _state.update { it.copy(isCreating = false) }
                    _effects.emit(BackupEffect.SaveFile(blob.base64, blob.fileName))
                }
                .onFailure {
                    Log.e(TAG, "file: FAILED — ${it::class.simpleName}")
                    _state.update { it.copy(isCreating = false, failed = true) }
                }
        }
    }

    /** The platform could not write where the user chose. Says so rather than doing nothing. */
    fun onFileSaveFailed() {
        Log.e(TAG, "file: the chosen location could not be written")
        _state.update { it.copy(failed = true) }
    }

    /** The platform reported the file was actually written. Only then does this count. */
    fun onFileSaved() {
        viewModelScope.launch {
            keyBackup.markBackedUp(BackupMethod.EncryptedFile)
            _effects.emit(BackupEffect.Done)
        }
    }

    fun onLeave() = _state.update { BackupFileUiState() }
}

data class BackupFileUiState(
    val passphrase: String = "",
    val strength: PassphraseStrength = PassphraseStrength.TooShort,
    val isCreating: Boolean = false,
    val failed: Boolean = false,
) {
    val canCreate: Boolean get() = passphrase.isNotEmpty() && !isCreating
}

/**
 * Export the key into Pubky Ring. **Loopky keeps its copy** — Ring imports the key, it does not take
 * custody of it, and the phrase the user wrote down is still valid. Copy implying otherwise
 * describes something that did not happen.
 */
class BackupRingViewModel(
    private val keyBackup: KeyBackupRepository,
    private val ringPresence: PubkyRingPresence,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BackupRingUiState(
            ringInstalled = ringPresence.canImportKey(),
            installUrl = ringPresence.installUrl,
        ),
    )
    val state: StateFlow<BackupRingUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<BackupEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<BackupEffect> = _effects.asSharedFlow()

    fun onExportClick() {
        viewModelScope.launch {
            keyBackup.ringExportUrl()
                .onSuccess { url ->
                    // Straight out to the OS. Never logged, never staged in a clipboard — the URL
                    // carries the phrase, and recents snapshots and IME clipboard history can see
                    // anything that passes through either.
                    _effects.emit(BackupEffect.OpenDeeplink(url))
                }
                .onFailure {
                    Log.e(TAG, "ringExport: FAILED — ${it::class.simpleName}")
                    _state.update { it.copy(failed = true) }
                }
        }
    }

    /** Ring was opened with the import link. We cannot see the other side, so this is the user's word. */
    fun onExportConfirmed() {
        viewModelScope.launch {
            keyBackup.markBackedUp(BackupMethod.PubkyRing)
            _effects.emit(BackupEffect.Done)
        }
    }

    fun onRingUnavailable() = _state.update { it.copy(ringInstalled = false) }

    /**
     * Send the user to install Ring. The screen used to state that Ring was missing beside a
     * *disabled* button and stop there — a dead end on the one screen whose purpose is getting the
     * key into Ring.
     */
    fun onInstallRingClick() {
        viewModelScope.launch { _effects.emit(BackupEffect.OpenInstallPage(_state.value.installUrl)) }
    }

    /** Re-check on return from the store, the way the signup screen does. */
    fun onScreenResumed() {
        if (_state.value.ringInstalled) return
        _state.update { it.copy(ringInstalled = ringPresence.canImportKey()) }
    }
}

data class BackupRingUiState(
    val ringInstalled: Boolean = false,
    val installUrl: String = "",
    val failed: Boolean = false,
)

sealed interface BackupEffect {
    data object Done : BackupEffect
    data class SaveFile(val base64: String, val fileName: String) : BackupEffect
    data class OpenDeeplink(val url: String) : BackupEffect

    /** The store listing for Pubky Ring, so "not installed" is not a dead end. */
    data class OpenInstallPage(val url: String) : BackupEffect

    /** Read the saved credential back, so the confirm step checks it rather than the user. */
    data object ReadBackFromPasswordManager : BackupEffect
}
