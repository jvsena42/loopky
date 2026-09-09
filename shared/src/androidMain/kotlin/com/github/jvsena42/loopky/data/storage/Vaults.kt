package com.github.jvsena42.loopky.data.storage

import android.content.Context
import com.github.jvsena42.loopky.util.Log
import com.liftric.kvault.KVault

/**
 * Open the keystore-backed vault named [service], recovering from an undecryptable one.
 *
 * `KVault` wraps `EncryptedSharedPreferences`, which throws from *construction* when the XML on
 * disk cannot be decrypted with the Android Keystore master key. Every caller here builds its vault
 * in a field initialiser, so that throw surfaces the moment Koin resolves the store — which is
 * during onboarding, on a device that has done nothing wrong.
 *
 * The way it happens is a restore. The vault file is ordinary shared prefs as far as backup is
 * concerned, but the key that decrypts it is hardware-bound and does not travel. Loopky now sets
 * `allowBackup="false"` so it should not arise again, but installs that predate that change carry
 * the restored file already, and a Keystore can also be invalidated on-device (a factory reset of
 * secure hardware, a device-admin wipe).
 *
 * **The reset is the last resort, not the first answer**, because it is irreversible and the
 * credential it destroys is the whole session: deleting the file on the first throw meant one
 * momentary Keystore failure signed the user out for good, where doing nothing would have cost a
 * single launch. The two failures are indistinguishable at the exception — both surface as a
 * `GeneralSecurityException` from the same constructor — and only their *persistence* separates
 * them, so the discriminator is to ask twice. An undecryptable file answers the same way every
 * time; a Keystore that was busy does not.
 *
 * Returns null only if even the reset fails, and every caller degrades to "no stored value" rather
 * than throwing.
 */
internal fun openVaultOrNull(context: Context, service: String): KVault? = synchronized(vaultLock) {
    openVault(context, service)?.let { return it }
    // The one transient cause with a name: `androidx.security.crypto` generates the master key on
    // first use and is not safe to do so concurrently, and Loopky builds four vaults from Koin
    // field initialisers that a worker and the UI can resolve at the same moment. [vaultLock] keeps
    // ours apart; this second attempt is what an interrupted first one needs to succeed.
    openVault(context, service)?.let {
        Log.w(TAG, "vault '$service' opened on the second attempt; the first failure was transient")
        return it
    }

    Log.e(TAG, "vault '$service' unreadable twice, resetting it")
    return runCatching {
        context.deleteSharedPreferences(service)
        KVault(context, service)
    }.onFailure {
        Log.e(TAG, "vault '$service' unavailable even after reset", it)
    }.getOrNull()
}

private fun openVault(context: Context, service: String): KVault? =
    runCatching { KVault(context, service) }
        .onFailure { Log.e(TAG, "vault '$service' would not open", it) }
        .getOrNull()

/**
 * Serializes vault construction across the four stores.
 *
 * Not thread-safety for its own sake: the master key is generated on first use, and two vaults
 * doing that at once is the transient failure the retry above exists to survive. Cheap — this runs
 * once per store for the life of the process.
 */
private val vaultLock = Any()

/**
 * Read [key] from a vault that may not exist, treating any failure as absence.
 *
 * A decrypt can fail per-entry as well as per-file, and a value that cannot be read is the same
 * thing as no value to every caller here.
 */
internal fun KVault?.stringOrNull(key: String): String? =
    this?.let { runCatching { it.string(key) }.getOrNull() }

private const val TAG = "Loopky/Vaults"
