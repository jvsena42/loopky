package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.RingLogin
import com.github.jvsena42.loopky.mcp.security.ClientKey
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
    /** The [ClientKey] of the address that started it, for the per-address cap. */
    val ip: String,
    val startedAt: Instant,
) {
    /** When the page last asked after it; a flow nobody polls is a tab that was closed, or never a tab at all. */
    @Volatile var lastPolledAt: Instant = startedAt
}

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
 *
 * The lock covers the maps and nothing that talks to the network. [RingLogin.start] is a relay
 * round trip and [RingLogin.cancel] another; held across either, one slow relay stalled every
 * other user's sign-in and every status poll server-wide. A slot is reserved under the lock,
 * the flow started outside it, and the entry filled in under it again.
 */
class PendingLogins(
    private val ringLogin: RingLogin,
    private val minter: TokenMinter,
    private val clock: Clock,
    private val limits: OAuthLimits,
) {
    private val mutex = Mutex()
    private val byTxn = HashMap<String, PendingAuthorization>()

    /** Slots handed out to flows still being started, counted against the caps like the started ones. */
    private val reservedByIp = HashMap<String, Int>()
    private val log = RedactingLogger("loopky-mcp.pending")

    val size: Int get() = byTxn.size

    /** Flows being started right now, for the test that holds the relay round trip outside the lock. */
    suspend fun reservedSlots(): Int = mutex.withLock { reservedByIp.values.sum() }

    suspend fun begin(request: AuthorizeRequest, ip: String): PendingStart {
        val key = ClientKey.of(ip)
        val refused = reserve(key)
        if (refused != null) return refused
        val login = runSuspendCatching { ringLogin.start() }.getOrElse { failure ->
            mutex.withLock { unreserveLocked(key) }
            return PendingStart.RingFailed(failure)
        }
        val pending = PendingAuthorization(
            txn = minter.opaque(),
            request = request,
            flowId = login.flowId,
            authorizationUrl = login.authorizationUrl,
            ip = key,
            startedAt = clock.instant(),
        )
        mutex.withLock {
            unreserveLocked(key)
            byTxn[pending.txn] = pending
        }
        return PendingStart.Started(pending)
    }

    /** Null once expired or abandoned, with the flow cancelled; the status endpoint then reports `expired`. */
    suspend fun find(txn: String): PendingAuthorization? {
        val expired = mutex.withLock {
            val pending = byTxn[txn] ?: return null
            if (isLive(pending, clock.instant())) {
                pending.lastPolledAt = clock.instant()
                return pending
            }
            byTxn.remove(txn)
            pending
        }
        cancelQuietly(expired)
        return null
    }

    /** Removes the entry without cancelling the flow — used once Ring has answered, so there is nothing to cancel. */
    suspend fun complete(txn: String) {
        mutex.withLock { byTxn.remove(txn) }
    }

    suspend fun sweep() {
        val expired = mutex.withLock { sweepLocked() }
        expired.forEach { cancelQuietly(it) }
    }

    /** Takes a slot for [key] under the caps, or says which cap refused it. Expired flows are swept first, outside the lock. */
    private suspend fun reserve(key: String): PendingStart? {
        val (refusal, expired) = mutex.withLock {
            val expired = sweepLocked()
            val reserved = reservedByIp.values.sum()
            val refusal = when {
                byTxn.size + reserved >= limits.pending.global -> PendingStart.TooManyGlobally
                byTxn.values.count { it.ip == key } + (reservedByIp[key] ?: 0) >= limits.pending.perIp -> PendingStart.TooManyForIp
                else -> {
                    reservedByIp[key] = (reservedByIp[key] ?: 0) + 1
                    null
                }
            }
            refusal to expired
        }
        expired.forEach { cancelQuietly(it) }
        return refusal
    }

    private fun unreserveLocked(key: String) {
        val left = (reservedByIp[key] ?: 1) - 1
        if (left <= 0) reservedByIp.remove(key) else reservedByIp[key] = left
    }

    /**
     * Still inside the relay window, and polled recently enough to be a page somebody has open.
     * The second bound is what stops `<img src="…/authorize?…">` on any page, or a script that
     * never polls, from holding every slot for the whole window from its visitors' addresses.
     */
    private fun isLive(pending: PendingAuthorization, now: Instant): Boolean =
        pending.startedAt.plus(limits.lifetimes.pendingLogin).isAfter(now) &&
            pending.lastPolledAt.plus(limits.lifetimes.unpolledLogin).isAfter(now)

    /** The caller must hold [mutex]. Returns what to cancel, outside it. */
    private fun sweepLocked(): List<PendingAuthorization> {
        val now = clock.instant()
        val expired = byTxn.values.filterNot { isLive(it, now) }
        expired.forEach { byTxn.remove(it.txn) }
        return expired
    }

    private suspend fun cancelQuietly(pending: PendingAuthorization) {
        runSuspendCatching { ringLogin.cancel(pending.flowId) }
            .onFailure { log.warn("Cancelling an expired Ring flow failed", it) }
    }
}
