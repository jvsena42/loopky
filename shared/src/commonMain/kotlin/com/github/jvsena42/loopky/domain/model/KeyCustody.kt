package com.github.jvsena42.loopky.domain.model

/**
 * Who holds the secret key for the signed-in account.
 *
 * This is the question every backup surface asks — the Settings nag, the home banner, the
 * export row, the sign-out warning — and it is deliberately answerable **without reading the
 * secret**. Nothing here carries key material, so a [KeyCustody] is safe in a `UiState`, safe to
 * log, and safe to hand to a composable.
 */
sealed interface KeyCustody {

    /**
     * Pubky Ring holds the key, or nobody is signed in.
     *
     * The only state that existed before local keys: Loopky had a session secret scoped to its own
     * storage and never the key that authorised it. Nothing to back up here, because there is
     * nothing on this device that losing the device would destroy.
     */
    data object External : KeyCustody

    /**
     * Loopky holds the key on this device — created here, or restored from a phrase or file.
     *
     * [backedUpBy] is the whole point of carrying this state around: an account whose key exists
     * only in one app's keystore is one lost phone away from gone, and Loopky is the only thing
     * that can say so.
     */
    data class Loopky(
        val pubky: String,
        val backedUpBy: Set<BackupMethod> = emptySet(),
        /**
         * Whether a recovery *phrase* can be shown for this key.
         *
         * False for a key restored from a recovery file: the file yields a secret key, and
         * deriving the words back out of it is not possible — BIP-39 runs one way. Those accounts
         * get the file, Ring and password-manager options and must not be offered a phrase screen
         * that would render nothing.
         */
        val hasPhrase: Boolean = true,
        /** False for a key held only so it can be registered: it has no account to sign in to. */
        val registered: Boolean = true,
    ) : KeyCustody {
        val isBackedUp: Boolean get() = backedUpBy.isNotEmpty()
    }
}

/**
 * A way the user has put their key somewhere that survives losing this device.
 *
 * Recorded as a set rather than a boolean because they are not equivalent and the UI says which
 * ones are done: a phrase written on paper and a file in Drive fail in different ways, and having
 * done one is not a reason to stop offering the others.
 */
enum class BackupMethod {
    /** The twelve words, shown once and confirmed back through the quiz. */
    RecoveryPhrase,

    /** Saved into the platform credential manager (Google Password Manager, Bitwarden, …). */
    PasswordManager,

    /** An Argon2id-encrypted `recovery.pkarr`, exported through the system file picker. */
    EncryptedFile,

    /**
     * Imported into Pubky Ring over `pubkyring://`.
     *
     * Ring **imports** the key; it does not take custody of it. Loopky keeps its own copy and its
     * session, and the words the user wrote down are still valid — so nothing here means the key
     * moved or that another copy stopped existing.
     */
    PubkyRing,
}

/**
 * A freshly minted, freshly registered account, with the phrase that recovers it.
 *
 * The mnemonic travels back to the caller exactly once, so the backup step can show it. It is
 * already in the keystore by then; this is not the only copy.
 */
data class LocalAccount(val pubky: String, val mnemonic: String)

/**
 * Signing out would destroy the only copy of a key nobody has backed up.
 *
 * Thrown by `IdentityRepository.signOut` rather than returned as a state, because the caller has
 * to *stop* — the point is that the UI raises a confirm before the key is gone.
 */
class UnbackedUpLocalKey(val pubky: String) :
    RuntimeException("The local key for $pubky has never been backed up")
