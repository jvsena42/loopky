package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.RingLogin
import com.github.jvsena42.loopky.mcp.security.RedactingLogger
import com.github.jvsena42.loopky.mcp.store.McpStore
import com.github.jvsena42.loopky.mcp.vault.CredentialVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeoutException

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
    private val bounds: UnlinkBounds = UnlinkBounds(),
) {
    private val log = RedactingLogger("loopky-mcp.unlink")

    /** Where a revoke that outlived its bound finishes, so a hung homeserver holds a thread here and nothing else. */
    private val revokes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Revokes on the homeserver (best effort: the row goes regardless) and deletes the link, every token and code. */
    suspend fun unlink(pubky: String): Boolean {
        val link = store.links.find(pubky) ?: return false
        val secret = runCatching { vault.open(pubky, link.credential) }
            .onFailure { log.error("Link for $pubky holds a credential the vault cannot open; deleting it unrevoked", it) }
            .getOrNull()
        if (secret != null) {
            revokeWithin(secret)
                .onFailure { log.warn("Revoking the grant for $pubky on the homeserver failed; deleting the link anyway", it) }
        }
        store.tokens.deleteAllFor(pubky)
        store.links.delete(pubky)
        log.info("Unlinked $pubky")
        return true
    }

    /** After a family ends: a user whose last family is gone has no way back but a fresh sign-in, so the grant goes too. */
    suspend fun unlinkIfOrphaned(pubky: String) {
        if (!store.tokens.hasLiveFamily(pubky, clock.instant())) unlink(pubky)
    }

    /**
     * [RingLogin.revoke] under [UnlinkBounds.revokeTimeout]. A timeout *around* an FFI call waits for it
     * anyway (§13.10), so the revoke runs as a coroutine of its own and is abandoned at the
     * bound: the sweep used to await one lapsed link on an unresponsive homeserver forever,
     * and every later sweep step with it.
     */
    private suspend fun revokeWithin(secret: String): Result<Unit> {
        val attempt = revokes.async { ringLogin.revoke(secret) }
        return withTimeoutOrNull(bounds.revokeTimeout.toMillis()) { attempt.await() }
            ?: Result.failure(TimeoutException("the homeserver did not answer within ${bounds.revokeTimeout}"))
    }

    /** Links whose every refresh token has lapsed: the inactivity bound (#486). Returns how many were unlinked. */
    suspend fun sweepExpired(): Int {
        val now = clock.instant()
        store.codes.deleteExpired(now)
        store.clients.deleteUnused(createdBefore = now.minus(bounds.registrationGrace))
        return store.tokens.pubkysWithoutLiveFamily(now, touchedBefore = now.minus(bounds.sweepGrace)).count { unlink(it) }
    }
}

/** The sweep's and the revoke's bounds, each a default a test narrows. */
data class UnlinkBounds(
    /** A link younger than this is skipped by the sweep: between approval and the code exchange it has no family yet. */
    val sweepGrace: Duration = Duration.ofHours(1),
    /** A registration that has not started a flow by then never will; Claude registers and authorizes in one go. */
    val registrationGrace: Duration = Duration.ofDays(1),
    /** The most one homeserver revoke may take before the unlink goes on without it. */
    val revokeTimeout: Duration = Duration.ofSeconds(REVOKE_TIMEOUT_SECONDS),
) {
    private companion object {
        const val REVOKE_TIMEOUT_SECONDS = 15L
    }
}
