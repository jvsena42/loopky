package com.github.jvsena42.loopky.mcp.auth

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnlinkTest {
    @Test
    fun `unlink revokes the grant on the homeserver, then deletes the link and every token`() = oauthTest {
        val clientId = registerClient()
        val tokens = tokensFor(clientId)
        assertTrue(server.unlinker.unlink(PUBKY))
        assertEquals(listOf(SESSION_SECRET), ring.revoked)
        assertNull(harness.store.links.find(PUBKY))
        assertNull(server.verifier.verify(tokens.access))
        assertEquals("invalid_grant", refresh(tokens.refresh, clientId).json()["error"]?.jsonPrimitive?.content)
        assertFalse(server.unlinker.unlink(PUBKY), "nothing left to unlink")
    }

    @Test
    fun `a homeserver that refuses the revoke does not keep the link alive`() = oauthTest {
        val clientId = registerClient()
        tokensFor(clientId)
        ring.revokeResult = Result.failure(IllegalStateException("homeserver down"))
        assertTrue(server.unlinker.unlink(PUBKY))
        assertEquals(listOf(SESSION_SECRET), ring.revoked, "the revoke was attempted")
        assertNull(harness.store.links.find(PUBKY))
    }

    @Test
    fun `a homeserver that never answers the revoke does not hold the unlink, or the sweep behind it`() = oauthTest {
        val clientId = registerClient()
        tokensFor(clientId)
        val unlinker = Unlinker(harness.store, harness.vault, ring, clock, revokeTimeout = Duration.ofMillis(50))
        ring.revokeGate = CompletableDeferred()
        assertTrue(unlinker.unlink(PUBKY))
        assertEquals(listOf(SESSION_SECRET), ring.revoked, "the revoke was attempted")
        assertNull(harness.store.links.find(PUBKY), "the link waited on the homeserver")
    }

    @Test
    fun `revoked(pubky) deletes the link and kills every token without calling Ring`() = oauthTest {
        val clientId = registerClient()
        val tokens = tokensFor(clientId)
        server.sessionSecrets.revoked(PUBKY)
        assertNull(server.verifier.verify(tokens.access), "the next request is a 401")
        assertNull(server.sessionSecrets.sessionSecretFor(PUBKY))
        assertEquals(HttpStatusCode.BadRequest, refresh(tokens.refresh, clientId).status)
        assertTrue(ring.revoked.isEmpty(), "Ring already revoked it; there is nothing to revoke")
    }

    @Test
    fun `the sweep unlinks accounts whose every family has lapsed, and spares fresh links`() = oauthTest {
        val clientId = registerClient()
        tokensFor(clientId)
        val other = "8um71us3fyw6h8wbcxb5ar3rwusy1a6u49956ikzojg3gcwd1dty"
        clock.advance(Duration.ofDays(30))
        exchange(codeFor(clientId, verifier = VERIFIER + "-b", pubky = other, secret = "other-secret"), clientId, VERIFIER + "-b")
        clock.advance(Duration.ofDays(61))
        assertEquals(1, server.unlinker.sweepExpired())
        assertNull(harness.store.links.find(PUBKY))
        assertNotNull(harness.store.links.find(other), "refreshed 30 days later, still inside the bound")
        assertEquals(listOf(SESSION_SECRET), ring.revoked)

        val fresh = "y4euc58gnmxun9ybni8tqsm8ggg5ry3sjkyzn6dgqmdtbdugkbog"
        exchange(codeFor(clientId, verifier = VERIFIER + "-c", pubky = fresh, secret = "fresh-secret"), clientId, VERIFIER + "-c")
        harness.store.tokens.wipeAll()
        assertEquals(1, server.unlinker.sweepExpired(), "only the link outside the grace window is swept")
        assertNull(harness.store.links.find(other))
        assertNotNull(harness.store.links.find(fresh), "a link inside the grace window is never swept")
    }

    @Test
    fun `the second connection for a user keeps the link when the first disconnects`() = oauthTest {
        val claude = registerClient("Claude")
        val desktop = registerClient("Claude Desktop")
        val first = tokensFor(claude)
        val second = tokensFor(desktop, verifier = VERIFIER + "-desktop")
        revoke(first.refresh)
        assertNotNull(harness.store.links.find(PUBKY))
        assertNotNull(server.verifier.verify(second.access))
        assertTrue(ring.revoked.isEmpty())
        revoke(second.refresh)
        assertNull(harness.store.links.find(PUBKY))
        assertEquals(1, ring.revoked.size)
    }
}
