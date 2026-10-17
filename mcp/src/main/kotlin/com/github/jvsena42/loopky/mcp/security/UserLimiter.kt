package com.github.jvsena42.loopky.mcp.security

import kotlinx.coroutines.sync.Semaphore
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** Thrown by [UserLimiter.withOperation] when the user already has [UserLimits.maxConcurrentOperations] running. */
class UserBusyException(val pubky: String) : IllegalStateException("$pubky already has an operation running")

data class UserLimits(
    val writes: RateLimitPolicy = RateLimitPolicy.perMinute(count = 30, burst = 10),
    val maxConcurrentOperations: Int = 1,
    val dailyByteBudget: Long = DEFAULT_DAILY_BYTES,
) {
    companion object {
        /** A tenth of the free-tier quota (§8.5): a runaway agent cannot fill a user's homeserver in a day. */
        const val DEFAULT_DAILY_BYTES = 100L * 1024 * 1024
    }
}

/**
 * The per-user limits the tools half applies (#486): a token bucket on writes, one operation at a
 * time, and a daily byte budget. The homeserver quota is shared with the user's own app use and
 * 507 is terminal, so the budget refuses *before* the write, not after.
 */
class UserLimiter(
    private val limits: UserLimits = UserLimits(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val writes = TokenBucketLimiter(limits.writes, clock)
    private val operations = HashMap<String, Semaphore>()
    private val budgets = HashMap<String, DayBudget>()
    private val lock = Any()

    private class DayBudget(val day: LocalDate, var usedBytes: Long)

    fun tryWrite(pubky: String): Boolean = writes.tryAcquire(pubky)

    fun writeRetryAfterSeconds(pubky: String): Long = writes.retryAfterSeconds(pubky)

    /** Runs [block] holding the user's slot, or throws [UserBusyException] without waiting. */
    suspend fun <T> withOperation(pubky: String, block: suspend () -> T): T {
        val semaphore = synchronized(lock) { operations.getOrPut(pubky) { Semaphore(limits.maxConcurrentOperations) } }
        if (!semaphore.tryAcquire()) throw UserBusyException(pubky)
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }

    /** Charges [bytes] against today's budget, UTC day, or refuses and charges nothing. */
    fun tryChargeBytes(pubky: String, bytes: Long): Boolean = synchronized(lock) {
        val budget = today(pubky)
        if (budget.usedBytes + bytes > limits.dailyByteBudget) {
            false
        } else {
            budget.usedBytes += bytes
            true
        }
    }

    fun bytesRemainingToday(pubky: String): Long = synchronized(lock) {
        (limits.dailyByteBudget - today(pubky).usedBytes).coerceAtLeast(0)
    }

    private fun today(pubky: String): DayBudget {
        val day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val existing = budgets[pubky]
        if (existing != null && existing.day == day) return existing
        return DayBudget(day, 0).also { budgets[pubky] = it }
    }
}
