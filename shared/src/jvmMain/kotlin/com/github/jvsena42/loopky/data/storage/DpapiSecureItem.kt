package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.platform.isWindows
import com.github.jvsena42.loopky.util.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The two DPAPI calls, behind a seam so everything around them can be tested off Windows.
 *
 * Null means the call failed. There is no richer failure here on purpose: `CryptProtectData` and
 * `CryptUnprotectData` report through `GetLastError`, and the only distinction the caller acts on
 * is "this blob is not ours to read" versus "it worked" — which is the same answer either way, and
 * is reported to the user as the item not answering rather than as the session being absent.
 */
internal interface DpapiCrypto {
    fun protect(plaintext: ByteArray): ByteArray?
    fun unprotect(ciphertext: ByteArray): ByteArray?
}

/**
 * The session as a DPAPI blob in the config home (#301).
 *
 * **What this buys, stated at its ceiling rather than above it.** `CryptProtectData` at user scope
 * keys the blob to the account's logon secret, so it is useless to another account on the same
 * machine, and a disk taken offline yields it only to somebody who can also recover that secret —
 * or, on a domain, who holds the DPAPI backup key, which decrypts any user's master keys by design.
 * That is strictly better than the plaintext file it replaces and is not the same as safe.
 * It is *not* protection from code running as this user —
 * anything that can read the file can call `CryptUnprotectData` on it, exactly as anything that can
 * run `security` can read the macOS Keychain item. What it removes is a plaintext credential sitting
 * in a directory people tar up into bug reports.
 *
 * **No platform-default gate, and that is the one place this deliberately differs from
 * [keychainEligible].** The Keychain is gated on the config home being the default one, because an
 * item addressed by service name is shared by every config home — so `LOOPKY_CONFIG_HOME=/tmp/x
 * loopky login` would otherwise overwrite the caller's real session. This blob lives *inside* the
 * config home, so a disposable home already gets a disposable blob and the gate would buy nothing.
 * Copying it would also cost something real: every CI smoke step sets `LOOPKY_CONFIG_HOME`, so a
 * gated store would be silently bypassed in the one place it gets exercised.
 *
 * **The fixed entropy is a speed bump and is described as one.** It is compiled into the binary, so
 * anyone holding `loopky.exe` holds it; it adds nothing against a targeted attacker. What it does is
 * make the blob refuse generic "decrypt every DPAPI blob in this profile" tooling, which is cheap
 * enough to be worth having and dishonest to call more. It is versioned with the filename, because
 * changing it invalidates every existing blob — which reads to a user as being signed out for no
 * reason unless the file changes name at the same time.
 */
internal class DpapiSecureItem(
    private val blob: Path,
    private val crypto: DpapiCrypto = Win32Dpapi,
) : SecureItem {

    override val location: String = "$blob (DPAPI, encrypted to this Windows account)"

    override fun read(): SecureItemRead {
        if (!Files.exists(blob)) return SecureItemRead.Missing
        val ciphertext = runCatching { Files.readAllBytes(blob) }.getOrElse {
            return SecureItemRead.Failed("could not read $blob: ${it.message}")
        }
        // **Failed, never Missing.** A blob written by another account, or carried over from a
        // reimaged machine, will never decrypt — and reporting that as "there is no session" would
        // send someone to `loopky login` to fix a file that will keep not decrypting afterwards.
        val plaintext = crypto.unprotect(ciphertext)
            ?: return SecureItemRead.Failed(
                "$blob did not decrypt — it belongs to another account, or this profile's " +
                    "master key is gone",
            )
        return SecureItemRead.Found(String(plaintext, Charsets.UTF_8))
    }

    /** Presence without decrypting, which is what [SecureItem.exists] asks for and DPAPI makes free. */
    override fun exists(): Boolean? = Files.exists(blob)

    override fun write(value: String): Result<Unit> {
        val ciphertext = crypto.protect(value.toByteArray(Charsets.UTF_8))
            ?: return Result.failure(IllegalStateException("CryptProtectData refused to encrypt the session"))
        return runCatching {
            OwnerOnly.createDirectories(blob.parent)
            // Written to a sibling and renamed, so a process killed mid-write cannot leave a
            // truncated blob under the name the next command reads — the same shape `JsonFileStore`
            // and `replaceInPlace` use, and the reason the temp file is owner-only from birth.
            val temp = OwnerOnly.createTempFile(blob.parent, blob.fileName.toString(), ".tmp")
            // `finally` with a flag rather than catch-cleanup-rethrow: the failure propagates
            // untouched to the enclosing `runCatching`, and the cleanup also runs for the
            // throwables a `catch (Exception)` would have missed — which is the case that would
            // otherwise leave an encrypted temp file sitting in the config home for good.
            var moved = false
            try {
                Files.write(temp, ciphertext)
                Files.move(temp, blob, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                moved = true
            } finally {
                if (!moved) Files.deleteIfExists(temp)
            }
            // The move carries the temp file's ACL on Windows, but a POSIX rename does not carry a
            // mode onto an existing target, so the destination is narrowed again. Logged rather than
            // failed: losing a session to a strict guarantee is the worse outcome, and
            // `OwnerOnly.supported` is what a caller asks before *claiming* the property.
            if (!OwnerOnly.restrict(blob)) {
                Log.d(TAG, "wrote $blob but could not make it owner-only")
            }
        }
    }

    override fun delete(): Result<Unit> = runCatching { Files.deleteIfExists(blob) }.map { }

    private companion object {
        const val TAG = "Loopky/Dpapi"
    }
}

/**
 * Whether this host gets the DPAPI store.
 *
 * Windows and nothing else — see [DpapiSecureItem] for why there is no config-home condition here,
 * which is the asymmetry with [keychainEligible] a reader will otherwise assume is an oversight.
 */
internal fun dpapiEligible(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    isWindows(osName)

/**
 * The blob's name, carrying the same `v1` marker as [SESSION_STORAGE_KEY].
 *
 * Versioned because the entropy and the payload shape are baked into what the file means: changing
 * either makes every existing blob undecryptable, and a user meeting that sees an unexplained
 * sign-out unless the name moves with it.
 */
internal const val SESSION_BLOB_FILE = "session.v1.dpapi"
