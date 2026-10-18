package com.github.jvsena42.loopky.mcp.security

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedEgressTest {

    @Test
    fun `Anthropic's range is the default and matches by prefix`() {
        val egress = SharedEgress.parse(SharedEgress.ANTHROPIC)
        assertTrue(egress.contains("160.79.104.0"))
        assertTrue(egress.contains("160.79.111.255"))
        assertFalse(egress.contains("160.79.112.0"))
        assertFalse(egress.contains("160.79.103.255"))
        assertFalse(egress.contains("203.0.113.7"))
    }

    @Test
    fun `several ranges, either family, and names never match`() {
        val egress = SharedEgress.parse("10.0.0.0/8, 2001:db8::/32 192.0.2.1")
        assertTrue(egress.contains("10.200.1.1"))
        assertTrue(egress.contains("[2001:db8:ffff::1]"))
        assertTrue(egress.contains("192.0.2.1"))
        assertFalse(egress.contains("192.0.2.2"))
        assertFalse(egress.contains("2001:db9::1"))
        assertFalse(egress.contains("localhost"))
        assertFalse(SharedEgress.NONE.contains("160.79.104.1"))
    }

    @Test
    fun `a malformed range refuses to start`() {
        assertFailsWith<IllegalArgumentException> { SharedEgress.parse("160.79.104.0/40") }
        assertFailsWith<IllegalArgumentException> { SharedEgress.parse("anthropic.com/21") }
    }
}
