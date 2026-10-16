package com.github.jvsena42.loopky.mcp.security

import com.github.jvsena42.loopky.mcp.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    private val clock = MutableClock(Instant.parse("2026-10-16T12:00:00Z"))

    @Test
    fun `a bucket spends its burst, refills over time and keys apart`() {
        val limiter = TokenBucketLimiter(RateLimitPolicy(burst = 2, perSecond = 1.0), clock)
        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        assertEquals(1, limiter.retryAfterSeconds("a"))
        assertTrue(limiter.tryAcquire("b"), "another key has its own bucket")
        clock.advance(Duration.ofMillis(1_000))
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        clock.advance(Duration.ofMinutes(5))
        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"), "refill never exceeds the burst")
    }

    @Test
    fun `pruning drops refilled buckets and keeps the busy ones`() {
        val limiter = TokenBucketLimiter(RateLimitPolicy(burst = 1, perSecond = 1.0), clock, maxKeys = 3)
        assertTrue(limiter.tryAcquire("old"))
        clock.advance(Duration.ofMillis(900))
        assertTrue(limiter.tryAcquire("busy1"))
        assertTrue(limiter.tryAcquire("busy2"))
        clock.advance(Duration.ofMillis(100))
        assertTrue(limiter.tryAcquire("new"), "the map was at its cap, so the refilled bucket was pruned to make room")
        assertFalse(limiter.tryAcquire("busy1"), "a busy bucket survived the prune")
        assertFalse(limiter.tryAcquire("busy2"))
    }

    @Test
    fun `the user limiter gates writes, one operation at a time and a daily byte budget`() = runTest {
        val limiter = UserLimiter(
            UserLimits(writes = RateLimitPolicy(burst = 1, perSecond = 0.0), maxConcurrentOperations = 1, dailyByteBudget = 100),
            clock,
        )
        assertTrue(limiter.tryWrite("u"))
        assertFalse(limiter.tryWrite("u"))
        assertTrue(limiter.tryWrite("v"))

        val gate = CompletableDeferred<Unit>()
        val running = async { limiter.withOperation("u") { gate.await(); "done" } }
        yield()
        assertFailsWith<UserBusyException> { limiter.withOperation("u") { "second" } }
        assertEquals("other user", limiter.withOperation("v") { "other user" })
        gate.complete(Unit)
        assertEquals("done", running.await())
        assertEquals("again", limiter.withOperation("u") { "again" }, "the slot is released after the block")

        assertTrue(limiter.tryChargeBytes("u", 60))
        assertFalse(limiter.tryChargeBytes("u", 50), "over budget: nothing is charged")
        assertEquals(40, limiter.bytesRemainingToday("u"))
        assertTrue(limiter.tryChargeBytes("u", 40))
        assertEquals(0, limiter.bytesRemainingToday("u"))
        clock.advance(Duration.ofDays(1))
        assertEquals(100, limiter.bytesRemainingToday("u"), "a new UTC day, a new budget")
    }
}
