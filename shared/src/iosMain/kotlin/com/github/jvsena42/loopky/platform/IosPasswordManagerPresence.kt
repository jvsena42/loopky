package com.github.jvsena42.loopky.platform

/**
 * iOS cannot offer this, and the answer is not "not yet".
 *
 * There is no API by which an app writes an arbitrary secret into the user's Passwords app or
 * iCloud Keychain as a credential they can later look up: `ASCredentialIdentityStore` advertises
 * identities for autofill rather than storing secrets, and `SecItemAdd` writes into the app's own
 * keychain — which is where the key already lives, so it would back up nothing. The phrase, the
 * recovery file and Pubky Ring are the three real options here.
 */
class IosPasswordManagerPresence : PasswordManagerPresence {
    override fun canSave(): Boolean = false
}
