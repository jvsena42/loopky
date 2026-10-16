package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.RingLogin
import com.github.jvsena42.loopky.mcp.security.RedactingLogger
import com.github.jvsena42.loopky.mcp.store.McpStore
import com.github.jvsena42.loopky.mcp.vault.CredentialVault
import com.github.jvsena42.loopky.util.runSuspendCatching
import java.time.Clock
import java.time.Duration

/**
 * Ends a link the way #486 requires: the grant is revoked on the homeserver first, so a leaked
 * copy of the row is worthless, and only then is the row deleted. The MCP half calls this too
 * (a disconnect it learns of first), and the sweep covers links nobody refreshes any more.
 */
class Unlinker(
    private val store: McpStore,
    private val vault: CredentialVault,
    private val ringLogin: RingLogin,
    private val clock: Clock,
    /** A link younger than this is skipped by the sweep: between approval and the code exchange it has no family yet. */
    private val sweepGrace: Duration = Duration.ofHours(1),
) {
    private val log = RedactingLogger("loopky-mcp.unlink")

    /** Revokes on the homeserver (best effort: the row goes regardless) and deletes the link and every token. */
    suspend fun unlink(pubky: String): Boolean {
        val link = store.links.find(pubky) ?: return false
        val secret = runCatching { vault.open(pubky, link.credential) }
            .onFailure { log.error("Link for $pubky holds a credential the vault cannot open; deleting it unrevoked", it) }
            .getOrNull()
        if (secret != null) {
            runSuspendCatching { ringLogin.revoke(secret).getOrThrow() }
                .onFailure { log.warn("Revoking the grant for $pubky on the homeserver failed; deleting the link anyway", it) }
        }
        store.tokens.revokeAllFor(pubky, clock.instant())
        store.links.delete(pubky)
        log.info("Unlinked $pubky")
        return true
    }

    /** After a family ends: a user whose last family is gone has no way back but a fresh sign-in, so the grant goes too. */
    suspend fun unlinkIfOrphaned(pubky: String) {
        if (!store.tokens.hasLiveFamily(pubky, clock.instant())) unlink(pubky)
    }

    /** Links whose every refresh token has lapsed: the inactivity bound (#486). Returns how many were unlinked. */
    suspend fun sweepExpired(): Int {
        store.codes.deleteExpired(clock.instant())
        val now = clock.instant()
        return store.tokens.pubkysWithoutLiveFamily(now, touchedBefore = now.minus(sweepGrace)).count { unlink(it) }
    }
}
