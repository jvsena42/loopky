package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.RingLogin
import com.github.jvsena42.loopky.mcp.security.RedactingLogger
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

/** What `/authorize` validated, held until Ring answers. [authorizationUrl] is rendered into the page once and nowhere else. */
class PendingAuthorization(
    val txn: String,
    val request: AuthorizeRequest,
    val flowId: String,
    val authorizationUrl: String,
    val ip: String,
    val expiresAt: Instant,
)

/** The `/authorize` fields a pending login is built from, after validation. */
class AuthorizeRequest(
    val clientId: String,
    val clientName: String,
    val redirectUri: String,
    val codeChallenge: String,
    val state: String?,
    val resource: String,
)

sealed interface PendingStart {
    data class Started(val pending: PendingAuthorization) : PendingStart
    data object TooManyForIp : PendingStart
    data object TooManyGlobally : PendingStart
    data class RingFailed(val cause: Throwable) : PendingStart
}

/**
 * In memory on purpose: a Ring flow is a handle inside this process's FFI, so a pending login
 * cannot outlive the process, and a persisted row would only ever describe a flow that is gone.
 * The txn the page polls with is random and keyed here, never stored anywhere else.
 */
class PendingLogins(
    private val ringLogin: RingLogin,
    private val minter: TokenMinter,
    private val clock: Clock,
    private val limits: OAuthLimits,
) {
    private val mutex = Mutex()
    private val byTxn = HashMap<String, PendingAuthorization>()
    private val log = RedactingLogger("loopky-mcp.pending")

    val size: Int get() = byTxn.size

    /** Holds the lock across [RingLogin.start] so the caps are counted against flows that actually exist. */
    suspend fun begin(request: AuthorizeRequest, ip: String): PendingStart = mutex.withLock {
        sweepLocked()
        if (byTxn.size >= limits.pending.global) return PendingStart.TooManyGlobally
        if (byTxn.values.count { it.ip == ip } >= limits.pending.perIp) return PendingStart.TooManyForIp
        val login = runSuspendCatching { ringLogin.start() }.getOrElse { return PendingStart.RingFailed(it) }
        val pending = PendingAuthorization(
            txn = minter.opaque(),
            request = request,
            flowId = login.flowId,
            authorizationUrl = login.authorizationUrl,
            ip = ip,
            expiresAt = clock.instant().plus(limits.lifetimes.pendingLogin),
        )
        byTxn[pending.txn] = pending
        PendingStart.Started(pending)
    }

    /** Null once expired, with the flow cancelled; the status endpoint then reports `expired`. */
    suspend fun find(txn: String): PendingAuthorization? = mutex.withLock {
        val pending = byTxn[txn] ?: return null
        if (pending.expiresAt.isAfter(clock.instant())) return pending
        byTxn.remove(txn)
        cancelQuietly(pending)
        null
    }

    /** Removes the entry without cancelling the flow — used once Ring has answered, so there is nothing to cancel. */
    suspend fun complete(txn: String) {
        mutex.withLock { byTxn.remove(txn) }
    }

    suspend fun sweep() = mutex.withLock { sweepLocked() }

    private suspend fun sweepLocked() {
        val now = clock.instant()
        val expired = byTxn.values.filter { !it.expiresAt.isAfter(now) }
        expired.forEach { byTxn.remove(it.txn) }
        expired.forEach { cancelQuietly(it) }
    }

    private suspend fun cancelQuietly(pending: PendingAuthorization) {
        runSuspendCatching { ringLogin.cancel(pending.flowId) }
            .onFailure { log.warn("Cancelling an expired Ring flow failed", it) }
    }
}
