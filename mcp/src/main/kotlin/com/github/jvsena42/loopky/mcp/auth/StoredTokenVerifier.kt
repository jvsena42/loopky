package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.AccessTokenVerifier
import com.github.jvsena42.loopky.mcp.McpConfig
import com.github.jvsena42.loopky.mcp.Principal
import com.github.jvsena42.loopky.mcp.store.McpStore
import java.time.Clock
import java.time.Duration

/**
 * Hash lookup, expiry, audience and a still-present link — the link check is what makes
 * [com.github.jvsena42.loopky.mcp.SessionSecrets.revoked] take effect on the very next request.
 */
class StoredTokenVerifier(
    private val store: McpStore,
    private val config: McpConfig,
    private val clock: Clock,
) : AccessTokenVerifier {
    override suspend fun verify(accessToken: String): Principal? {
        if (!accessToken.startsWith(TokenKind.Access.prefix)) return null
        val now = clock.instant()
        val row = store.tokens.findAccess(TokenHash.of(accessToken)) ?: return null
        if (row.revokedAt != null || !row.expiresAt.isAfter(now) || row.audience != config.resourceUrl) return null
        val link = store.links.find(row.pubky) ?: return null
        if (Duration.between(link.lastUsedAt, now) >= TOUCH_INTERVAL) store.links.touch(row.pubky, now)
        return Principal(pubky = row.pubky, clientId = row.clientId)
    }

    private companion object {
        /** Last-used is a day-granularity fact; writing it on every tool call would be the busiest row in the file. */
        val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
