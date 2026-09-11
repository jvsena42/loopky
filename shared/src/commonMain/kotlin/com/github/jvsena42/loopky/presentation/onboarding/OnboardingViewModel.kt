package com.github.jvsena42.loopky.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.redactAuthUrl
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.platform.PubkyRingPresence
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The onboarding / Pubky Ring login screen: Idle → Starting → AwaitingApproval → Verifying →
 * Success / Error. Side effects (open deeplink, install page, navigate home) go through [effects]
 * so platform UIs react without leaking platform APIs into the VM.
 */
class OnboardingViewModel(
    private val identityRepository: IdentityRepository,
    private val ringPresence: PubkyRingPresence,
    private val pubkyRingInstallUrl: String = DEFAULT_INSTALL_URL,
) : ViewModel() {
    private val _state = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Restoring)
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<OnboardingEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<OnboardingEffect> = _effects.asSharedFlow()

    private var signInJob: Job? = null

    init {
        Log.d(TAG, "init: checking persisted session")
        viewModelScope.launch {
            // The screen sits on the branded splash until this resolves, so every exit from here
            // — including a store that throws — has to move the state on or the splash never lifts.
            val persisted = runSuspendCatching { identityRepository.loadPersistedSession() }
                .onFailure { Log.e(TAG, "init: reading the persisted session failed", it) }
                .getOrNull()
            if (persisted != null) {
                Log.d(TAG, "init: found persisted session pubky=${persisted.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
                _state.update { OnboardingUiState.Success(persisted) }
                _effects.emit(OnboardingEffect.NavigateHome)
            } else {
                Log.d(TAG, "init: no persisted session")
                _state.update { OnboardingUiState.Idle }
            }
        }
    }

    /**
     * Begin a Pubky Ring authorisation. [handoff] decides only whether we *also* fire the deeplink
     * — the relay poll underneath is identical either way, which is why a tablet can be signed in
     * from a phone, and why this is a branch in the effect rather than a second flow.
     */
    fun onSignInClick(handoff: RingHandoff = RingHandoff.ThisDevice) {
        if (signInJob?.isActive == true) {
            Log.d(TAG, "onSignInClick: ignored — sign-in already in progress")
            return
        }
        signInJob = viewModelScope.launch {
            Log.d(TAG, "onSignInClick: state=Starting, calling beginSignIn handoff=$handoff")
            _state.update { OnboardingUiState.Starting }
            val handleResult = identityRepository.beginSignIn()
            val handle = handleResult.getOrElse { error ->
                Log.e(TAG, "onSignInClick: beginSignIn FAILED — ${error::class.simpleName}: ${error.message}", error)
                _state.update { OnboardingUiState.Error(error.toErrorReason()) }
                return@launch
            }
            Log.d(TAG, "onSignInClick: got authUrl=${handle.authUrl.redactAuthUrl()}")

            // Asked here rather than in the UI so both platforms answer it the same way: the same
            // probe already gates signup, and iOS's `canOpenURL` needs the `pubkyauth` entry in
            // LSApplicationQueriesSchemes that this object documents. Read once and reused below,
            // because `update` may run its lambda more than once and this is a package-manager
            // round trip, not a field.
            val ringInstalledHere = ringPresence.isInstalled()
            _state.update {
                OnboardingUiState.AwaitingApproval(
                    authUrl = handle.authUrl,
                    handoff = handoff,
                    ringInstalledHere = ringInstalledHere,
                )
            }
            Log.d(TAG, "onSignInClick: state=AwaitingApproval, handoff=$handoff, ring=$ringInstalledHere")
            // Only when Ring is meant to be on this device *and* actually is. Firing it for the QR
            // path would bounce the user out to whatever claims `pubkyauth://`; firing it with
            // nothing installed used to end the flow on "Pubky Ring isn't installed", which is a
            // dead end for someone whose key is in Ring on another phone — the authorisation is
            // live either way, so the UI can offer it as a code to scan instead.
            if (handoff == RingHandoff.ThisDevice && ringInstalledHere) {
                _effects.emit(OnboardingEffect.OpenDeeplink(handle.authUrl))
            }

            Log.d(TAG, "onSignInClick: awaiting Pubky Ring approval…")
            // No deadline, on purpose (#299). The wait is a blocking FFI call nothing here can
            // interrupt, so a timeout only decided what to do with an approval that arrived after it
            // — and it threw that approval away. That is the ordinary path: Android freezes Loopky
            // while the user approves in the signer, and the approval is collected on their return.
            // The FFI ends the wait itself when the relay stays unreachable, and Cancel is always
            // there; after a while the screen just says it is still waiting.
            val stillWaiting = markStillWaitingLater(handle.authUrl)
            val completion = handle.complete()
            stillWaiting.cancel()
            _state.update { OnboardingUiState.Verifying }
            Log.d(TAG, "onSignInClick: state=Verifying, completion.success=${completion.isSuccess}")

            completion
                .onSuccess { session ->
                    Log.d(TAG, "onSignInClick: SUCCESS pubky=${session.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
                    _state.update { OnboardingUiState.Success(session) }
                    _effects.emit(OnboardingEffect.NavigateHome)
                }
                .onFailure { err ->
                    Log.e(TAG, "onSignInClick: completion FAILED — ${err::class.simpleName}: ${err.message}", err)
                    val reason = err.toSignInReason()
                    _state.update { OnboardingUiState.Error(reason) }
                    // A pubky Ring authorised that the homeserver has no account for is not an
                    // error to read and shrug at — it is a specific, fixable situation with its own
                    // screen. The old copy sent the user back to Ring, which structurally cannot
                    // create the account: that needs a signup token, and tokens come from Homegate,
                    // which lives here (#147).
                    if (reason == ErrorReason.NoHomeserverAccount) {
                        val pubky = (completion.getOrNull() ?: sessionPubkyOrNull())?.identity?.pubky
                        if (pubky != null) {
                            _effects.emit(OnboardingEffect.NavigateUnregistered(pubky))
                        }
                    }
                }
        }
    }

    private fun CoroutineScope.markStillWaitingLater(authUrl: String): Job = launch {
        delay(APPROVAL_NUDGE_MS)
        _state.update { current ->
            if (current is OnboardingUiState.AwaitingApproval && current.authUrl == authUrl) {
                current.copy(stillWaiting = true)
            } else {
                current
            }
        }
    }

    /**
     * The pubky Ring authorised, when we managed to learn it. Null where the failure happened
     * before a session was ever parsed, so the screen shows the error without the follow-up rather
     * than inventing a key to talk about.
     */
    private suspend fun sessionPubkyOrNull(): Session? =
        runSuspendCatching { identityRepository.currentSession() }.getOrNull()

    /**
     * Classify a failed approval. The only network the completion touches is the auth relay — the
     * profile fetch inside it is best-effort — so a transport failure here is the relay being
     * unreachable, not the user being offline. Anything unclassified is still an auth failure from
     * the user's point of view, not a mystery.
     */
    private fun Throwable.toSignInReason(): ErrorReason {
        return when (val reason = toErrorReason()) {
            ErrorReason.Offline -> ErrorReason.AuthRelayUnreachable
            ErrorReason.Unknown -> ErrorReason.AuthFailed
            // The homeserver answers 404 when it has no account for the pubky Ring just authorised.
            // Nothing else on this path can 404 for a *record* — no deck or profile is fetched yet
            // — so here, and only here, a not-found is always the account. That is why the remap
            // lives in the sign-in path rather than `toErrorReason`, which classifies reads too and
            // would turn "your library is empty" into "no account".
            ErrorReason.NotFound -> ErrorReason.NoHomeserverAccount
            else -> reason
        }
    }

    /**
     * Escape hatch from the QR panel: approve on *this* device after all, without restarting. The
     * same live authorisation, so the QR stays valid and the relay poll keeps running — a fresh
     * [onSignInClick] would invalidate a code the user may already be pointing a phone at.
     */
    fun onOpenRingOnThisDevice() {
        val awaiting = _state.value as? OnboardingUiState.AwaitingApproval ?: run {
            Log.w(TAG, "onOpenRingOnThisDevice: ignored — no authorisation in flight")
            return
        }
        viewModelScope.launch { _effects.emit(OnboardingEffect.OpenDeeplink(awaiting.authUrl)) }
    }

    /**
     * Back out of a sign-in still waiting on Ring, without leaving an error behind. Distinct from
     * [onDeeplinkUnavailable], which also cancels but lands on [ErrorReason.RingNotInstalled]:
     * closing the QR panel is not a problem. The abandoned authorisation expires on the relay —
     * there is nothing to revoke, and the URL is useless to anyone who did not already have it.
     */
    fun onCancelSignIn() {
        Log.d(TAG, "onCancelSignIn: user dismissed the handoff")
        signInJob?.cancel()
        signInJob = null
        _state.update { OnboardingUiState.Idle }
    }

    fun onGetRingClick() {
        viewModelScope.launch { _effects.emit(OnboardingEffect.OpenInstallPage(pubkyRingInstallUrl)) }
    }

    /**
     * Called by the UI when it cannot open the Pubky Ring deeplink (e.g. Ring not installed).
     * We cancel the in-flight sign-in job so `awaitAuthApproval` doesn't keep blocking, and
     * surface an actionable error.
     */
    fun onDeeplinkUnavailable() {
        Log.w(TAG, "onDeeplinkUnavailable: no handler for pubkyauth:// — aborting flow")
        signInJob?.cancel()
        signInJob = null
        _state.update { OnboardingUiState.Error(ErrorReason.RingNotInstalled) }
    }

    companion object {
        private const val TAG = "Loopky/OnboardingVM"
        private const val PUBKY_LOG_PREFIX_LEN = 8

        /**
         * When the waiting screen starts saying so. Not a deadline — the wait carries on — so it can
         * sit well short of how long approving can take (creating a key, writing down a phrase).
         */
        private const val APPROVAL_NUDGE_MS = 90 * 1000L

        /** Product landing page — forwards to the correct store for the user's platform. */
        const val DEFAULT_INSTALL_URL = "https://pubkyring.app"
    }
}
