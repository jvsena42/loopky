package com.github.jvsena42.loopky.mcp.tenant

import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.mcp.SessionSecrets
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The bounds a deployment puts on tenants. Every one of them exists because the server is shared:
 * a graph per user costs heap, a call costs a homeserver round trip, and one user's long import
 * must not be the reason another's `deck list` waits.
 */
data class TenantLimits(
    /** Open graphs kept at once; the least recently used is closed when a new one is needed. */
    val maxTenants: Int = DEFAULT_MAX_TENANTS,
    /** A graph nobody has used for this long is closed on the next call that looks. */
    val idleTtl: Duration = DEFAULT_IDLE_TTL,
    /** Calls in flight across every user. */
    val maxConcurrentCalls: Int = DEFAULT_MAX_CONCURRENT_CALLS,
    /** The most one call may run; what it had written by then stays written. */
    val callTimeout: Duration = DEFAULT_CALL_TIMEOUT,
    /** How long a call waits for the user's turn, or for a global slot, before it is refused. */
    val busyWait: Duration = DEFAULT_BUSY_WAIT,
) {
    private companion object {
        const val DEFAULT_MAX_TENANTS = 64
        val DEFAULT_IDLE_TTL = 30.minutes
        const val DEFAULT_MAX_CONCURRENT_CALLS = 8
        val DEFAULT_CALL_TIMEOUT = 10.minutes
        val DEFAULT_BUSY_WAIT = 2.seconds
    }
}

/**
 * One [Tenant] per linked user, opened on first use and kept while it is being used.
 *
 * **Isolation is the security property.** A call for one pubky runs against the graph opened from
 * that pubky's secret and no other: the map is keyed by the verified principal, a graph is never
 * handed to a call for a different key, and an evicted graph is closed rather than reused. The
 * tests in `TenantPoolTest` hold that under concurrency and across eviction, which is where a
 * cache would get it wrong.
 *
 * **One operation at a time per user**, because the repositories serialize a deck's manifest
 * writes with an in-process mutex and nothing else: two calls on one graph are two read-modify-
 * writes of the same record. A second call waits [TenantLimits.busyWait] and is then refused as
 * [TenantRefusal.UserBusy] rather than queued — an agent told "busy" waits; one left queued behind
 * a ten-minute import times out and retries into the queue again.
 *
 * **A refused credential is reported, never retried.** The FFI re-mints a bearer from the stored
 * grant on its own, so a session the homeserver still refuses after that is a revoked grant
 * (#486). [revoke] tells [SessionSecrets], drops the graph, and remembers the refused secret so
 * [isRevoked] can answer the HTTP layer until the user links again with a different one.
 */
class TenantPool(
    private val factory: TenantFactory,
    private val secrets: SessionSecrets,
    private val limits: TenantLimits = TenantLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry {
        val lock = Mutex()
        var tenant: Tenant? = null
        var secretHash: String? = null
        var lastUsed = 0L
        var inUse = false
        var closeWhenReleased = false
    }

    private val poolLock = Mutex()

    /** Access-ordered, so iteration starts at the least recently used. Guarded by [poolLock]. */
    private val entries = LinkedHashMap<String, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    private val gate = Semaphore(limits.maxConcurrentCalls)

    /** Pubky to the hash of the secret the homeserver refused. Never the secret. */
    private val revoked = ConcurrentHashMap<String, String>()

    /** Runs [block] on [pubky]'s graph, opening it first if need be. */
    suspend fun <T> withTenant(pubky: String, block: suspend (Tenant) -> T): T {
        val entry = entryFor(pubky)
        withTimeoutOrNull(limits.busyWait) { entry.lock.lock() } ?: throw TenantRefusal.UserBusy(pubky)
        try {
            // Inside the user's turn, so a user waiting for a global slot holds nothing another
            // user needs; outside the call timeout, which is for the command and not the queue.
            withTimeoutOrNull(limits.busyWait) { gate.acquire() } ?: throw TenantRefusal.ServerBusy()
            try {
                val tenant = entry.tenant ?: open(pubky, entry)
                return bounded(block, tenant)
            } finally {
                gate.release()
            }
        } finally {
            release(pubky, entry)
            entry.lock.unlock()
        }
    }

    /**
     * Reports [pubky]'s credential as refused by the homeserver: [SessionSecrets.revoked], the
     * graph closed, and the refused secret remembered for [isRevoked]. Safe to call from inside
     * [withTenant]'s block — the graph is closed once the block returns.
     */
    suspend fun revoke(pubky: String) {
        val hash = poolLock.withLock { entries[pubky]?.secretHash }
            ?: secrets.sessionSecretFor(pubky)?.let(::sha256)
        if (hash != null) revoked[pubky] = hash
        secrets.revoked(pubky)
        evict(pubky)
    }

    /**
     * True while the credential on record for [pubky] is the one the homeserver refused, or
     * there is none left: the HTTP layer answers such a caller 401 so the client reconnects. A
     * *different* secret means the user has linked again, and the memory is dropped.
     */
    suspend fun isRevoked(pubky: String): Boolean {
        val refused = revoked[pubky] ?: return false
        val current = secrets.sessionSecretFor(pubky)
        return when {
            current == null -> true
            sha256(current) == refused -> true
            else -> {
                revoked.remove(pubky, refused)
                false
            }
        }
    }

    /** Closes [pubky]'s graph, now or when the call holding it returns. */
    suspend fun evict(pubky: String) {
        val closing = poolLock.withLock {
            val entry = entries[pubky] ?: return
            if (entry.inUse) {
                entry.closeWhenReleased = true
                null
            } else {
                entries.remove(pubky)
                entry.tenant
            }
        }
        closing?.closeQuietly()
    }

    /** Closes every graph idle past [TenantLimits.idleTtl]. */
    suspend fun sweep() {
        poolLock.withLock { sweepLocked(clock()) }.forEach { it.closeQuietly() }
    }

    /** The pubkys with an open graph right now, for the tests that hold isolation. */
    suspend fun openTenants(): Set<String> = poolLock.withLock {
        entries.filterValues { it.tenant != null }.keys.toSet()
    }

    suspend fun close() {
        val closing = poolLock.withLock {
            entries.values.mapNotNull { it.tenant }.also { entries.clear() }
        }
        closing.forEach { it.closeQuietly() }
    }

    private suspend fun entryFor(pubky: String): Entry {
        val closing = mutableListOf<Tenant>()
        val entry = poolLock.withLock {
            closing += sweepLocked(clock())
            val existing = entries[pubky]
            val entry = existing ?: Entry().also { entries[pubky] = it }
            closing += trimLocked(keep = pubky)
            entry.inUse = true
            entry
        }
        closing.forEach { it.closeQuietly() }
        return entry
    }

    private suspend fun open(pubky: String, entry: Entry): Tenant {
        val (secret, hash) = linkedSecret(pubky)
        val tenant = try {
            factory.open(secret)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // The one failure worth classifying here: a secret the homeserver refuses outright is
            // a revoked grant, and reporting it is what stops the next call opening it again.
            if (ExitCode.of(error) == ExitCode.SessionExpired) {
                revoked[pubky] = hash
                secrets.revoked(pubky)
                throw TenantRefusal.Revoked(pubky)
            }
            throw error
        }
        requireOwnedBy(tenant, pubky)
        entry.tenant = tenant
        entry.secretHash = hash
        return tenant
    }

    /** The secret on record and its hash, refused up front when it is the one the homeserver already did. */
    private suspend fun linkedSecret(pubky: String): Pair<String, String> {
        val secret = secrets.sessionSecretFor(pubky) ?: throw TenantRefusal.NotLinked(pubky)
        val hash = sha256(secret)
        if (revoked[pubky] == hash) throw TenantRefusal.Revoked(pubky)
        return secret to hash
    }

    private suspend fun <T> bounded(block: suspend (Tenant) -> T, tenant: Tenant): T {
        val outcome = withTimeoutOrNull(limits.callTimeout) { Outcome(block(tenant)) }
            ?: throw TenantRefusal.TimedOut(limits.callTimeout.inWholeSeconds)
        return outcome.value
    }

    private suspend fun release(pubky: String, entry: Entry) {
        val closing = poolLock.withLock {
            entry.inUse = false
            entry.lastUsed = clock()
            if (entry.closeWhenReleased) {
                entries.remove(pubky, entry)
                entry.tenant
            } else {
                null
            }
        }
        closing?.closeQuietly()
    }

    /** The caller must hold [poolLock]. Returns what to close, outside the lock. */
    private fun sweepLocked(now: Long): List<Tenant> {
        val idle = entries.filterValues { !it.inUse && now - it.lastUsed > limits.idleTtl.inWholeMilliseconds }
        idle.keys.forEach { entries.remove(it) }
        return idle.values.mapNotNull { it.tenant }
    }

    /** The caller must hold [poolLock]. Drops least-recently-used graphs nobody is running on. */
    private fun trimLocked(keep: String): List<Tenant> {
        val closing = mutableListOf<Tenant>()
        val iterator = entries.entries.iterator()
        while (entries.size > limits.maxTenants && iterator.hasNext()) {
            val (pubky, entry) = iterator.next()
            if (entry.inUse || pubky == keep) continue
            iterator.remove()
            entry.tenant?.let { closing += it }
        }
        return closing
    }

    private class Outcome<T>(val value: T)

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f

        fun sha256(secret: String): String =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).joinToString("") { "%02x".format(it) }

        /** A graph that fails to close is still gone from the pool; nothing a caller can do about it. */
        suspend fun Tenant.closeQuietly() {
            withContext(NonCancellable) {
                runSuspendCatching { close() }
            }
        }

        /**
         * Defence in depth over the vault: a credential stored under the wrong pubky must fail
         * here, never serve one user's calls on another's graph.
         */
        suspend fun requireOwnedBy(tenant: Tenant, pubky: String) {
            if (tenant.session.identity.pubky == pubky) return
            tenant.closeQuietly()
            throw TenantRefusal.NotLinked(pubky)
        }
    }
}
