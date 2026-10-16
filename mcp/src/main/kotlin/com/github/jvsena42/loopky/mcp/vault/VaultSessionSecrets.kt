package com.github.jvsena42.loopky.mcp.vault

import com.github.jvsena42.loopky.mcp.SessionSecrets
import com.github.jvsena42.loopky.mcp.auth.Unlinker
import com.github.jvsena42.loopky.mcp.security.RedactingLogger
import com.github.jvsena42.loopky.mcp.store.McpStore

/**
 * [revoked] goes through [Unlinker.unlink], the one way a link ends: the homeserver has, as far
 * as the MCP half can tell, refused the credential already, but a classification can be wrong —
 * an expired bearer answers 401 too — and deleting the only copy of a credential the homeserver
 * still honours would leave the grant live with nothing able to revoke it. The revoke is
 * idempotent and best effort, so attempting it costs one round trip when it was right.
 */
class VaultSessionSecrets(
    private val store: McpStore,
    private val vault: CredentialVault,
    private val unlinker: Unlinker,
) : SessionSecrets {
    private val log = RedactingLogger("loopky-mcp.secrets")

    override suspend fun sessionSecretFor(pubky: String): String? {
        val link = store.links.find(pubky) ?: return null
        return runCatching { vault.open(pubky, link.credential) }
            .onFailure { log.error("Credential for $pubky failed to open", it) }
            .getOrNull()
    }

    override suspend fun revoked(pubky: String) {
        log.info("Grant for $pubky was refused upstream; unlinking")
        unlinker.unlink(pubky)
    }
}
