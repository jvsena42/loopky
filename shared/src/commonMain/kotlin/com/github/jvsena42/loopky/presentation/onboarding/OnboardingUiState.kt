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
     * Carries [authUrl] because on a big screen the URL *is* the UI: a tablet user's key normally
     * lives in Ring on their phone, so the way in is a QR code the phone can scan rather than a
     * deeplink to an app that isn't on this device. The URL is a one-shot capability — anyone who
     * reads it can complete this sign-in — so it is rendered and never logged unredacted, and it
     * dies with the state.
     *
     * @param handoff how the user was sent to Ring, which decides whether this screen shows a
     *  spinner (Ring is already in the foreground on this device) or the QR code.
     * @param ringInstalledHere whether anything on *this* device answers `pubkyauth://`, which is
     *  the only thing that makes the QR panel's "open it here instead" escape hatch worth
     *  offering — a dead button on the one screen a user cannot get past is worse than no button.
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
 * knows whether Ring is installed here and how wide the screen is, and the same shared ViewModel
 * has to serve a phone, a tablet and an iPad.
 */
enum class RingHandoff {
    /** Ring is on this device; open it over the `pubkyauth://` deeplink. */
    ThisDevice,

    /** Ring is on the user's phone; show the auth URL as a QR code for it to scan. */
    AnotherDevice,
}
