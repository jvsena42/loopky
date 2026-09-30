package com.github.jvsena42.loopky.platform

/**
 * Whether this device can put the recovery phrase into a password manager.
 *
 * Platform-provided via Koin (like [PubkyRingPresence]) rather than `expect`/`actual`, and
 * deliberately **only the question**, not the act. Saving raises a system sheet and so needs an
 * Activity and a lifecycle; the shared ViewModel therefore emits an effect and the platform layer
 * performs it, the same division Speak and Listen use. All that has to cross into shared code is
 * whether to offer the button at all.
 *
 * False hides the offer instead of failing it. A screen that shows "Save to password manager" and
 * then explains it cannot is worse than one that never made the offer — and on iOS, where writing
 * an arbitrary secret into the Passwords app is not something an app may do, false is the honest
 * and permanent answer.
 */
interface PasswordManagerPresence {
    /** True when a save can actually be attempted. */
    fun canSave(): Boolean
}
