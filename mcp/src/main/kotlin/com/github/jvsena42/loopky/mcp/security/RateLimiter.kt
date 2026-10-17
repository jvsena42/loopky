package com.github.jvsena42.loopky.mcp.security

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import java.time.Clock
import kotlin.math.ceil
import kotlin.math.min

/** [burst] tokens to start and at most, refilled at [perSecond]. */
data class RateLimitPolicy(val burst: Int, val perSecond: Double) {
    companion object {
        fun perMinute(count: Int, burst: Int = count) = RateLimitPolicy(burst, count / SECONDS_PER_MINUTE)
        private const val SECONDS_PER_MINUTE = 60.0
    }
}

/**
 * A token bucket per key. Keys are pruned once the map grows past [maxKeys]: first the buckets
 * that have refilled completely — a full bucket and a missing one behave the same — and then,
 * if the table is still full, the half refilled longest ago. Never the whole table: clearing it
 * reset every limit at once, the attacker's included, and a /64 of IPv6 addresses fills a table
 * of ten thousand at will.
 */
class TokenBucketLimiter(
    private val policy: RateLimitPolicy,
    private val clock: Clock = Clock.systemUTC(),
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
) {
    private class Bucket(var tokens: Double, var lastRefillMillis: Long)

    private val buckets = HashMap<String, Bucket>()
    private val lock = Any()

    fun tryAcquire(key: String, cost: Double = 1.0): Boolean = synchronized(lock) {
        val bucket = refilled(key)
        if (bucket.tokens >= cost) {
            bucket.tokens -= cost
            true
        } else {
            false
        }
    }

    /** Whole seconds until one token is back; what `Retry-After` should say. */
    fun retryAfterSeconds(key: String): Long = synchronized(lock) {
        val bucket = refilled(key)
        if (bucket.tokens >= 1.0) 0 else ceil((1.0 - bucket.tokens) / policy.perSecond).toLong().coerceAtLeast(1)
    }

    private fun refilled(key: String): Bucket {
        val now = clock.millis()
        val bucket = buckets.getOrPut(key) {
            if (buckets.size >= maxKeys) prune(now)
            Bucket(policy.burst.toDouble(), now)
        }
        val elapsedSeconds = (now - bucket.lastRefillMillis) / MILLIS_PER_SECOND
        bucket.tokens = min(policy.burst.toDouble(), bucket.tokens + elapsedSeconds * policy.perSecond)
        bucket.lastRefillMillis = now
        return bucket
    }

    private fun prune(now: Long) {
        val fullAfter = policy.burst / policy.perSecond * MILLIS_PER_SECOND
        buckets.values.removeIf { now - it.lastRefillMillis >= fullAfter }
        if (buckets.size < maxKeys) return
        val stalest = buckets.entries.sortedBy { it.value.lastRefillMillis }.take(buckets.size / 2).map { it.key }
        stalest.forEach { buckets.remove(it) }
    }

    private companion object {
        const val DEFAULT_MAX_KEYS = 10_000
        const val MILLIS_PER_SECOND = 1_000.0
    }
}

class RateLimitConfig {
    lateinit var limiter: TokenBucketLimiter
}

/**
 * Route-scoped: `install(RateLimitByIp) { limiter = … }`. Keyed on `request.origin.remoteHost`
 * through [ClientKey] — the proxy's address unless `XForwardedHeaders` is installed ahead of it;
 * behind Caddy that plugin is what makes the key the client and not `127.0.0.1` for everyone.
 */
val RateLimitByIp = createRouteScopedPlugin("RateLimitByIp", ::RateLimitConfig) {
    val limiter = pluginConfig.limiter
    onCall { call ->
        val key = ClientKey.of(call.request.origin.remoteHost)
        if (!limiter.tryAcquire(key)) {
            call.response.header(HttpHeaders.RetryAfter, limiter.retryAfterSeconds(key).toString())
            call.respondText("Too many requests", status = HttpStatusCode.TooManyRequests)
        }
    }
}
