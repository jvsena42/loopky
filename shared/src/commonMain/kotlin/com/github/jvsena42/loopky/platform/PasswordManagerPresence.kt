package com.github.jvsena42.loopky.platform

/**
 * Whether this device can put the recovery phrase into a password manager, and read it back out
 * on the restore screen.
 *
 * Platform-provided via Koin (like [PubkyRingPresence]) rather than `expect`/`actual`, and
 * deliberately **only the question**, not the act. Saving raises a system sheet and so needs an
 * Activity and a lifecycle; the shared ViewModel therefore emits an effect and the platform layer
 * performs it, the same division Speak and Listen use. All that has to cross into shared code is
 * whether to offer the button at all.
 *
 * False hides the offer instead of failing it. A screen that shows "Save to password manager" and
 * then explains it cannot is worse than one that never made the offer.
 */
interface PasswordManagerPresence {
    /** True when a save, or a read on restore, can actually be attempted. */
    fun canSave(): Boolean
}
