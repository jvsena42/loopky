package com.github.jvsena42.loopky.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressHeartbeatTest {

    private var clock = 0L
    private val lines = mutableListOf<String>()
    private val heartbeat = ProgressHeartbeat(lines::add, intervalMillis = 1_000L, now = { clock })

    @Test
    fun `a command that finishes inside the first interval says nothing`() {
        repeat(500) { heartbeat.report("$it/500 cards") }

        assertEquals(emptyList(), lines)
    }

    /** One line an interval, carrying the latest count rather than every count in between. */
    @Test
    fun `a slow command reports once an interval`() {
        listOf(400L to "1/9", 1_000L to "2/9", 1_500L to "3/9", 1_999L to "4/9", 2_000L to "5/9").forEach { (at, line) ->
            clock = at
            heartbeat.report("$line cards")
        }

        assertEquals(listOf("loopky: still working - 2/9 cards", "loopky: still working - 5/9 cards"), lines)
    }
}
