package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Platform-keystore-backed store for a secret key **Loopky itself holds** — minted here, or restored
 * from a recovery phrase or file.
 *
 * Until local keys, Loopky held only a session secret and Ring held the key (§7.8). A key created here
 * has nowhere else to live, and it *is* the account: lose it with no backup and the identity is gone.
 *
 * **Why the secrets vault and not the session one**, and none of the reasons is sign-out:
 *
 * 1. The key exists **before a session does** — on the generated-phrase screen `signUp` has not run.
 * 2. [custody] is asked constantly by surfaces that must never see a secret (the Settings nag, the
 *    export row, the sign-out warning), and deriving that from a session blob would mean decrypting
 *    the key to answer a question *about* the key.
 * 3. Handing custody to Ring later becomes a flag transition rather than a schema migration.
 *
 * **Nothing here logs, ever** — not the key, not the mnemonic, not a redacted prefix of either.
 *
 * **`internal` on purpose**: it is the only door to the secret, and only repositories go through it.
 * The rest of the app asks [custody], which carries no key material.
 */
internal interface LocalKeyStore {

    /**
     * Who holds the key, and whether it has been backed up. Emits the current value immediately,
     * then on every change. Never carries the secret.
     */
    val custody: Flow<KeyCustody>

    suspend fun save(key: LocalKey)

    /**
     * The stored key, or null when Ring holds it (or nobody is signed in).
     *
     * Deliberately a suspend *read of the vault* rather than a cached field: the secret then exists in
     * memory only while a caller is using it, instead of for the life of the process.
     */
    suspend fun current(): LocalKey?

    /**
     * Additive and idempotent: methods accumulate, because having written the words down is not a
     * reason to stop offering the encrypted file.
     */
    suspend fun markBackedUp(method: BackupMethod)

    /** Record that the held key now has a homeserver account. */
    suspend fun markRegistered()

    /**
     * Drop the key. **Unguarded by design**: whether it is safe to destroy an un-backed-up key is a
     * question about what the user was told, not about storage, so the refusal lives on
     * `IdentityRepository.signOut(force)` where the confirm can be raised. A guard here as well would
     * be a second place to keep in sync and get wrong.
     */
    suspend fun clear()
}

/**
 * A secret key Loopky holds, with everything needed to back it up. Never leaves the data layer:
 * repositories read it and hand the UI a [KeyCustody] or a one-shot value for a `FLAG_SECURE` screen.
 */
@Serializable
internal data class LocalKey(
    /** Hex, as the FFI's `secret_key` field gives it. */
    val secretKeyHex: String,
    /** z32, derived from the key — stored so [custody] can name the account without decrypting. */
    val pubky: String,
    /**
     * The twelve words, when we have them. Null for a key restored from a recovery file: that yields a
     * secret key, and BIP-39 derivation runs one way. Kept for the ones we do have because the confirm
     * quiz and "show my recovery phrase again" need the words themselves.
     */
    val mnemonic: String? = null,
    val backedUpBy: Set<BackupMethod> = emptySet(),
    /**
     * Whether this key has an account on a homeserver. False for one held only so it can be registered
     * — a phrase belonging to no account, or a mint whose `signUp` failed. The distinction decides
     * whether a retry may *register* this key or must mint a fresh one, and getting it wrong is how
     * someone ends up with a second identity while their first is stranded.
     */
    val registered: Boolean = true,
    /**
     * Where this key came from, which decides whether anything may adopt it. A [KeyOrigin.Minted] key
     * exists nowhere else, so it must be finished rather than abandoned; a [KeyOrigin.Restored] one can
     * be re-derived from what the user holds, which makes it safe to discard — and unsafe to silently
     * adopt into a signup the user started by asking for a *new* account.
     */
    val origin: KeyOrigin = KeyOrigin.Minted,
) {
    fun toCustody(): KeyCustody.Loopky = KeyCustody.Loopky(
        pubky = pubky,
        backedUpBy = backedUpBy,
        hasPhrase = mnemonic != null,
    )
}

/** How a held key came to be here. */
@Serializable
internal enum class KeyOrigin { Minted, Restored }

internal const val LOCAL_KEY_STORAGE_KEY = "identity.key.v1"
