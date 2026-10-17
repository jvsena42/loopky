package com.github.jvsena42.loopky.mcp.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    @Test
    fun `credentials of every shape are masked`() {
        val token = "lk_at_" + "A".repeat(43)
        val cases = mapOf(
            "pubkyauth:///?relay=https://r.test/&secret=abc&caps=/pub/loopky/:rw" to "pubkyauth://[redacted]",
            "token $token used" to "token lk_at_[redacted] used",
            "Authorization: Bearer abc.def-ghi" to "Authorization: Bearer [redacted]",
            "https://claude.ai/cb?code=lk_ac_x&state=xyz" to "https://claude.ai/cb?code=[redacted]&state=xyz",
            "grant_secret=eyJhbGci" to "grant_secret=[redacted]",
            "blob " + "b".repeat(40) + " end" to "blob [redacted] end",
        )
        cases.forEach { (input, expected) -> assertEquals(expected, Redaction.redact(input), input) }
    }

    @Test
    fun `ordinary identifiers survive`() {
        val pubky = "o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo"
        assertTrue(pubky.length > 40, "a pubky is long enough to look like a blob")
        assertEquals("Linked $pubky for client lkc_abc", Redaction.redact("Linked $pubky for client lkc_abc"))
        assertEquals("[redacted]", Redaction.redact(pubky.uppercase()), "only the z-base-32 alphabet is a pubky")
        assertEquals("flow-1 pending for Claude", Redaction.redact("flow-1 pending for Claude"))
        assertEquals("", Redaction.redact(null))
    }

    @Test
    fun `the logger redacts the message and the cause, and drops the stack`() {
        val captured = LogCapture.start()
        try {
            RedactingLogger("redaction-test").warn("refresh failed for lk_rt_abcdef", IllegalStateException("secret=hunter2"))
            val line = captured.lines().single()
            assertTrue("lk_rt_[redacted]" in line, line)
            assertTrue("secret=[redacted]" in line, line)
            assertFalse("hunter2" in line)
            assertFalse("at " in line)
        } finally {
            captured.stop()
        }
    }
}
