package com.github.jvsena42.loopky.platform

/**
 * iOS can ask, through one door: a password saved for `loopky.app`, the domain whose
 * `apple-app-site-association` names this app under `webcredentials`.
 *
 * No API writes an *arbitrary* secret into the Passwords app — `ASCredentialIdentityStore` only
 * advertises identities and `SecItemAdd` writes to the app's own keychain, where the key already
 * is. A shared web credential is the exception, and the Swift half (`PasswordManagerSheet`) uses
 * it. Whether the association actually holds on this device is only knowable by trying, so this
 * reports the capability and the save reports the outcome, as on Android.
 */
class IosPasswordManagerPresence : PasswordManagerPresence {
    override fun canSave(): Boolean = true
}
