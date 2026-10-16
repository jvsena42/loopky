package com.github.jvsena42.loopky.mcp.vault

import com.github.jvsena42.loopky.mcp.SessionSecrets
import com.github.jvsena42.loopky.mcp.security.RedactingLogger
import com.github.jvsena42.loopky.mcp.store.McpStore
import java.time.Clock

/**
 * [revoked] does not call Ring: the homeserver already refused the credential, which is how the
 * MCP half found out. It deletes the link and every token for the pubky, so the next request is
 * a 401 and Claude reconnects (#486).
 */
class VaultSessionSecrets(
    private val store: McpStore,
    private val vault: CredentialVault,
    private val clock: Clock,
) : SessionSecrets {
    private val log = RedactingLogger("loopky-mcp.secrets")

    override suspend fun sessionSecretFor(pubky: String): String? {
        val link = store.links.find(pubky) ?: return null
        return runCatching { vault.open(pubky, link.credential) }
            .onFailure { log.error("Credential for $pubky failed to open", it) }
            .getOrNull()
    }

    override suspend fun revoked(pubky: String) {
        store.tokens.revokeAllFor(pubky, clock.instant())
        store.links.delete(pubky)
        log.info("Grant for $pubky was revoked upstream; link removed")
    }
}
