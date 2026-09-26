package com.github.jvsena42.loopky.presentation.onboarding

sealed interface OnboardingEffect {
    /** Open the Pubky Ring app to approve sign-in. Platform uses system deeplink APIs. */
    data class OpenDeeplink(val url: String) : OnboardingEffect

    /** Navigate to the install page for Pubky Ring (store listing). */
    data class OpenInstallPage(val url: String) : OnboardingEffect

    /**
     * Ring authorised a pubky the homeserver has no account for.
     *
     * Its own destination rather than an error message, because the remedy is a flow: verify, then
     * register *that* key. Ring cannot do it — creating the account needs a token from Homegate,
     * and Homegate lives in Loopky.
     */
    data class NavigateUnregistered(val pubky: String) : OnboardingEffect
}
