package com.github.jvsena42.loopky.presentation.onboarding

import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.Session

sealed interface OnboardingUiState {
    /**
     * Cold start: the persisted session is being read back. The UI shows the branded splash
     * here, so a returning user never sees the sign-in CTA flash by on the way home.
     */
    data object Restoring : OnboardingUiState

    /** Resting state — no session to restore, CTA enabled. */
    data object Idle : OnboardingUiState

    /** Calling `startAuthFlow`, no deeplink yet. CTA disabled, spinner on button. */
    data object Starting : OnboardingUiState

    /**
     * The authorisation is live and we are waiting for Pubky Ring to POST back via the relay.
     *
     * Carries [authUrl] because the URL *is* the UI here, on every device: a key lives in Ring on
     * one phone, which is as likely to be the user's other phone as the one in their hand, so the
     * way in is a QR code rather than a deeplink guessed from what happens to be installed. The URL
     * is a one-shot capability — anyone who reads it can complete this sign-in — so it is rendered
     * and never logged unredacted, and it dies with the state.
     *
     * @param handoff where the user is expected to approve, which decides only whether the code is
     *  presented as a panel in the sign-in column or as a sheet over it.
     * @param ringInstalledHere whether anything on *this* device answers `pubkyauth://`, which is
     *  the only thing that makes the "open it here instead" escape hatch worth offering — a dead
     *  button on the one screen a user cannot get past is worse than no button.
     * @param stillWaiting set once the wait has run long enough to say so. Never a failure: an
     *  approval is still accepted whenever it arrives (#299).
     */
    data class AwaitingApproval(
        val authUrl: String,
        val handoff: RingHandoff,
        val ringInstalledHere: Boolean,
        val stillWaiting: Boolean = false,
    ) : OnboardingUiState

    /** Parsing the callback + persisting session. Full-screen progress overlay acceptable. */
    data object Verifying : OnboardingUiState

    /** Terminal success — the VM will also emit [OnboardingEffect.NavigateHome] once. */
    data class Success(val session: Session) : OnboardingUiState

    /** Sign-in failed; show message + retry CTA. */
    data class Error(val reason: ErrorReason) : OnboardingUiState
}

/**
 * Where the user is expected to approve the sign-in.
 *
 * Chosen by the UI from the window it is drawn in, not by the ViewModel: only the platform layer
 * knows how wide the screen is, and the same shared ViewModel has to serve a phone, a tablet and an
 * iPad. Both values show the same code — this picks the presentation and the copy around it, never
 * whether a deeplink fires.
 */
enum class RingHandoff {
    /** Ring is plausibly on this device; the code goes in a sheet over the sign-in screen. */
    ThisDevice,

    /** Ring is on the user's phone; the code takes the sign-in column itself. */
    AnotherDevice,
}
