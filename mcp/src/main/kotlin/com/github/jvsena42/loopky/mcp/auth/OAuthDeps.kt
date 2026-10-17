package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.McpConfig
import com.github.jvsena42.loopky.mcp.RingLogin
import com.github.jvsena42.loopky.mcp.security.RateLimitPolicy
import com.github.jvsena42.loopky.mcp.store.McpStore
import com.github.jvsena42.loopky.mcp.vault.CredentialVault
import java.time.Clock
import java.time.Duration

/** Everything the OAuth half is built from; [clock] and [limits] are what the tests replace. */
class OAuthDeps(
    val config: McpConfig,
    val store: McpStore,
    val vault: CredentialVault,
    val ringLogin: RingLogin,
    val clock: Clock = Clock.systemUTC(),
    val limits: OAuthLimits = OAuthLimits(),
)

data class Lifetimes(
    /** RFC 6749 §4.1.2 says ten minutes at most; a page that just redirected needs seconds. */
    val code: Duration = Duration.ofSeconds(CODE_SECONDS),
    val accessToken: Duration = Duration.ofHours(1),
    /** Inactivity bound: each rotation grants another 90 days, and a link nobody refreshes is unlinked (#486). */
    val refreshToken: Duration = Duration.ofDays(REFRESH_DAYS),
    /** The relay's window; a login not approved by then is cancelled so its FFI thread comes back. */
    val pendingLogin: Duration = Duration.ofMinutes(PENDING_MINUTES),
    /**
     * How long a login lives without the page polling for it. The page polls every two seconds,
     * but a phone's browser suspends a background tab while the user is over in Ring, so this
     * is minutes and not the seconds a poll interval would suggest.
     */
    val unpolledLogin: Duration = Duration.ofMinutes(UNPOLLED_MINUTES),
) {
    companion object {
        const val CODE_SECONDS = 60L
        const val REFRESH_DAYS = 90L
        const val PENDING_MINUTES = 5L
        const val UNPOLLED_MINUTES = 2L
    }
}

/**
 * Each pending login blocks an FFI thread for up to [Lifetimes.pendingLogin], so both bounds are
 * small on purpose. Per address means per [com.github.jvsena42.loopky.mcp.security.ClientKey]:
 * an IPv6 client is counted by its /64.
 */
data class PendingLoginCaps(val perIp: Int = 3, val global: Int = 50)

data class OAuthLimits(
    val lifetimes: Lifetimes = Lifetimes(),
    val pending: PendingLoginCaps = PendingLoginCaps(),
    val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    val maxClientNameLength: Int = DEFAULT_MAX_CLIENT_NAME,
    val requestRate: RateLimitPolicy = RateLimitPolicy.perMinute(count = 20, burst = 10),
    /** The authorize page polls every two seconds for up to five minutes. */
    val statusRate: RateLimitPolicy = RateLimitPolicy.perMinute(count = 60, burst = 30),
) {
    companion object {
        const val DEFAULT_MAX_BODY_BYTES = 16 * 1024
        const val DEFAULT_MAX_CLIENT_NAME = 100
    }
}
