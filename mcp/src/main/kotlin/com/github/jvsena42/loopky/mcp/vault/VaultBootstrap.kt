package com.github.jvsena42.loopky.mcp.vault

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.mcp.store.McpStore
import com.github.jvsena42.loopky.mcp.store.VaultMeta

/**
 * Pairs a master key with a database. The database remembers which key version sealed its rows
 * and a check blob under that key, so a server started with the wrong key file — rotated and not
 * swapped in, or swapped in and not rotated — refuses to start instead of failing every user's
 * first tool call with "credential failed to authenticate".
 */
object VaultBootstrap {
    /** A subject no pubky can be (z-base-32 has no NUL), so the check blob never doubles as a credential. */
    private const val CHECK_SUBJECT = "\u0000vault-check"
    private const val CHECK_VALUE = "ok"
    private const val FIRST_VERSION = 1

    suspend fun open(store: McpStore, key: MasterKey, environment: PubkyEnvironment): CredentialVault {
        val meta = store.links.readVaultMeta()
        if (meta == null) {
            val vault = CredentialVault(key, FIRST_VERSION, environment)
            store.links.writeVaultMeta(VaultMeta(FIRST_VERSION, vault.seal(CHECK_SUBJECT, CHECK_VALUE)))
            return vault
        }
        val vault = CredentialVault(key, meta.keyVersion, environment)
        val opened = runCatching { vault.open(CHECK_SUBJECT, meta.keyCheck) }.getOrNull()
        if (opened != CHECK_VALUE) {
            throw MasterKeyException(
                "Master key does not open this database (key version ${meta.keyVersion}); " +
                    "was the key rotated without replacing the key file, or the file replaced without `admin rotate-key`?",
            )
        }
        return vault
    }

    /** Re-seals every link and the check blob under [newKey] as key version + 1, in one transaction. */
    suspend fun rotate(store: McpStore, current: CredentialVault, newKey: MasterKey, environment: PubkyEnvironment): Int {
        val next = CredentialVault(newKey, current.keyVersion + 1, environment)
        val meta = VaultMeta(next.keyVersion, next.seal(CHECK_SUBJECT, CHECK_VALUE))
        return store.links.rewrapAll(meta) { link -> current.rewrap(link.pubky, link.credential, next) }
    }
}
