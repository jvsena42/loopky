package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.security.LogCapture
import com.github.jvsena42.loopky.mcp.security.RedactingLogger
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plants every credential a flow produces and scans what reached the log. Logback's root logger
 * is captured at TRACE, so anything Ktor or the store logs is in the net too.
 */
class LogHygieneTest {
    @Test
    fun `a full flow, a failure and a reuse leave no credential in the log`() = oauthTest {
        val capture = LogCapture.start()
        try {
            val clientId = registerClient()
            val page = authorize(clientId).bodyAsText()
            val txn = txnOf(page)
            val ringUrl = ring.authUrl(ring.started.last())
            ring.approve()
            val redirect = status(txn)["redirect"]!!.jsonPrimitive.content
            val code = codeOf(redirect)
            val exchanged = exchange(code, clientId)
            assertEquals(HttpStatusCode.OK, exchanged.status, exchanged.bodyAsText())
            val first = exchanged.json().tokens()
            val second = refresh(first.refresh, clientId).json().tokens()
            ring.revokeResult = Result.failure(IllegalStateException("homeserver said no to $SESSION_SECRET"))
            refresh(first.refresh, clientId)
            exchange(code, clientId)
            server.verifier.verify(second.access)
            assertTrue(ring.revoked.isNotEmpty(), "the reuse unlinked, and the failed revoke was logged")
            RedactingLogger("hygiene").info(
                "planted: $ringUrl $code ${first.access} ${first.refresh} $SESSION_SECRET txn=$txn Bearer ${second.access}",
            )

            val lines = capture.lines()
            assertTrue(lines.any { "Linked $PUBKY" in it }, "the flow did log, and named the user: $lines")
            val planted = listOf(
                ringUrl, "RELAYSECRET", code, first.access, first.refresh, second.access, second.refresh, SESSION_SECRET, txn, VERIFIER,
            )
            for (secret in planted) {
                val offending = lines.filter { secret in it }
                assertTrue(offending.isEmpty(), "found $secret in: $offending")
            }
            assertFalse(lines.any { "pubkyauth:///" in it })
        } finally {
            capture.stop()
        }
    }
}
