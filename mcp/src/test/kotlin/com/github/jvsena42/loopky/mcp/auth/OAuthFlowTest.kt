package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.security.RateLimitPolicy
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthFlowTest {
    @Test
    fun `metadata documents point at this server`() = oauthTest {
        val resource = http.get("/.well-known/oauth-protected-resource/mcp").json()
        assertEquals("https://mcp.test/mcp", resource["resource"]?.jsonPrimitive?.content)
        assertEquals("https://mcp.test", resource["authorization_servers"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals(resource, http.get("/.well-known/oauth-protected-resource").json())

        val server = http.get("/.well-known/oauth-authorization-server").json()
        assertEquals("https://mcp.test", server["issuer"]?.jsonPrimitive?.content)
        assertEquals("https://mcp.test/token", server["token_endpoint"]?.jsonPrimitive?.content)
        assertEquals("S256", server["code_challenge_methods_supported"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("none", server["token_endpoint_auth_methods_supported"]?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test
    fun `full flow - register, authorize, approve, exchange, verify, refresh, rotate`() = oauthTest {
        val registered = register("Claude")
        assertEquals(HttpStatusCode.Created, registered.status)
        assertEquals("no-store", registered.headers[HttpHeaders.CacheControl])
        val clientId = registered.json()["client_id"]!!.jsonPrimitive.content

        val page = authorize(clientId)
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertContains(html, "<strong>Claude</strong>")
        assertContains(html, "<strong>claude.ai</strong>")
        assertContains(html, "href=\"pubkyauth://")
        assertContains(html, "<svg")
        assertContains(html, "approve in Ring, then come back")
        assertEquals("DENY", page.headers["X-Frame-Options"])
        assertEquals("no-referrer", page.headers["Referrer-Policy"])
        assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
        val csp = assertNotNull(page.headers["Content-Security-Policy"])
        assertContains(csp, "script-src 'nonce-")
        assertFalse("unsafe-inline" in csp)
        assertEquals(1, Regex("nonce=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toSet().size)
        val txn = txnOf(html)

        assertEquals("pending", status(txn)["status"]?.jsonPrimitive?.content)
        ring.approve()
        val approved = status(txn)
        assertEquals("approved", approved["status"]?.jsonPrimitive?.content)
        val redirect = approved["redirect"]!!.jsonPrimitive.content
        assertTrue(redirect.startsWith("$CALLBACK?"), redirect)
        assertContains(redirect, "state=xyz")
        assertEquals("expired", status(txn)["status"]?.jsonPrimitive?.content, "a txn answers once")

        val link = assertNotNull(harness.store.links.find(PUBKY))
        assertFalse(SESSION_SECRET in String(link.credential, Charsets.ISO_8859_1), "the credential is sealed, not stored")
        assertEquals(SESSION_SECRET, harness.vault.open(PUBKY, link.credential))

        val code = codeOf(redirect)
        val exchanged = exchange(code, clientId)
        assertEquals(HttpStatusCode.OK, exchanged.status, exchanged.bodyAsText())
        assertEquals("no-store", exchanged.headers[HttpHeaders.CacheControl])
        val body = exchanged.json()
        assertEquals("Bearer", body["token_type"]?.jsonPrimitive?.content)
        assertEquals(3600, body["expires_in"]?.jsonPrimitive?.content?.toInt())
        val first = body.tokens()
        assertTrue(first.access.startsWith("lk_at_"))
        assertTrue(first.refresh.startsWith("lk_rt_"))
        assertNull(harness.store.tokens.findAccess(first.access), "the store holds hashes, never tokens")

        val principal = assertNotNull(server.verifier.verify(first.access))
        assertEquals(PUBKY, principal.pubky)
        assertEquals(clientId, principal.clientId)
        assertEquals(SESSION_SECRET, server.sessionSecrets.sessionSecretFor(PUBKY))

        val refreshed = refresh(first.refresh, clientId)
        assertEquals(HttpStatusCode.OK, refreshed.status, refreshed.bodyAsText())
        val second = refreshed.json().tokens()
        assertNotEquals(first.refresh, second.refresh)
        assertNotNull(server.verifier.verify(second.access))
        assertNotNull(server.verifier.verify(first.access), "the old access token lives until it expires")
    }

    @Test
    fun `registration refuses foreign redirects, confidential clients and long names`() = oauthTest {
        val foreign = register(redirects = listOf(CALLBACK, OTHER_CALLBACK))
        assertEquals(HttpStatusCode.BadRequest, foreign.status)
        assertEquals("invalid_redirect_uri", foreign.json()["error"]?.jsonPrimitive?.content)

        val confidential = register(extra = ""","token_endpoint_auth_method":"client_secret_basic"""")
        assertEquals("invalid_client_metadata", confidential.json()["error"]?.jsonPrimitive?.content)

        val longName = register(name = "x".repeat(500)).json()["client_name"]!!.jsonPrimitive.content
        assertEquals(100, longName.length)

        val garbage = http.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, garbage.status)
    }

    @Test
    fun `an oversized body is refused before it is parsed`() = oauthTest {
        val huge = http.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("{\"client_name\":\"" + "a".repeat(20_000) + "\"}")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
    }

    @Test
    fun `authorize refuses an unknown client or redirect in place, and other mistakes by redirect`() = oauthTest {
        val clientId = registerClient()
        assertEquals(HttpStatusCode.BadRequest, authorize("lkc_nobody").status)
        assertEquals(HttpStatusCode.BadRequest, authorize(clientId, redirect = OTHER_CALLBACK).status)
        assertEquals(HttpStatusCode.BadRequest, authorize(clientId, redirect = "$CALLBACK/").status, "exact match only")
        assertTrue(ring.started.isEmpty(), "no Ring flow for a refused request")

        val plain = authorize(clientId, overrides = mapOf("code_challenge_method" to "plain"))
        assertEquals(HttpStatusCode.Found, plain.status)
        assertContains(plain.headers[HttpHeaders.Location]!!, "error=invalid_request")
        assertContains(plain.headers[HttpHeaders.Location]!!, "state=xyz")

        val noPkce = authorize(clientId, overrides = mapOf("code_challenge" to null, "code_challenge_method" to null))
        assertContains(noPkce.headers[HttpHeaders.Location]!!, "error=invalid_request")

        val wrongResource = authorize(clientId, resource = "https://other.test/mcp")
        assertContains(wrongResource.headers[HttpHeaders.Location]!!, "error=invalid_target")

        val token = authorize(clientId, overrides = mapOf("response_type" to "token"))
        assertContains(token.headers[HttpHeaders.Location]!!, "error=unsupported_response_type")
        assertTrue(ring.started.isEmpty())
    }

    @Test
    fun `a wrong PKCE verifier burns the code`() = oauthTest {
        val clientId = registerClient()
        val code = codeFor(clientId)
        val wrong = exchange(code, clientId, verifier = VERIFIER.reversed())
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertEquals("invalid_grant", wrong.json()["error"]?.jsonPrimitive?.content)
        val retry = exchange(code, clientId)
        assertEquals("invalid_grant", retry.json()["error"]?.jsonPrimitive?.content, "a code is spent on first use, right or wrong")
    }

    @Test
    fun `the exchange checks redirect, client, resource and expiry`() = oauthTest {
        val clientId = registerClient()
        val other = registerClient("Other")
        val otherRedirect = exchange(codeFor(clientId), clientId, redirect = "$CALLBACK?x=1")
        assertEquals("invalid_grant", otherRedirect.json()["error"]?.jsonPrimitive?.content)
        assertEquals("invalid_grant", exchange(codeFor(clientId), other).json()["error"]?.jsonPrimitive?.content)
        assertEquals(
            "invalid_target",
            exchange(codeFor(clientId), clientId, resource = "https://other.test/mcp").json()["error"]?.jsonPrimitive?.content,
        )
        val stale = codeFor(clientId)
        clock.advance(Duration.ofSeconds(61))
        assertEquals("invalid_grant", exchange(stale, clientId).json()["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `replaying a code revokes what it bought, even after the sweep has run`() = oauthTest {
        val clientId = registerClient()
        val code = codeFor(clientId)
        val tokens = exchange(code, clientId).json().tokens()
        assertNotNull(server.verifier.verify(tokens.access))
        // The code is a minute old and expired; a sweep in between must not forget it was spent.
        clock.advance(Duration.ofMinutes(2))
        server.sweep()
        assertEquals("invalid_grant", exchange(code, clientId).json()["error"]?.jsonPrimitive?.content)
        assertNull(server.verifier.verify(tokens.access), "the replay was read as an unknown code and revoked nothing")
        assertEquals("invalid_grant", refresh(tokens.refresh, clientId).json()["error"]?.jsonPrimitive?.content)
        clock.advance(Duration.ofDays(2))
        server.sweep()
        val left = harness.store.db.transaction { c ->
            c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM auth_codes").getInt(1) }
        }
        assertEquals(0, left)
    }

    @Test
    fun `reusing a rotated refresh token revokes the whole family`() = oauthTest {
        val clientId = registerClient()
        val first = tokensFor(clientId)
        val second = refresh(first.refresh, clientId).json().tokens()
        val third = refresh(second.refresh, clientId).json().tokens()
        assertNotNull(server.verifier.verify(third.access))

        val reuse = refresh(first.refresh, clientId)
        assertEquals(HttpStatusCode.BadRequest, reuse.status)
        assertEquals("invalid_grant", reuse.json()["error"]?.jsonPrimitive?.content)
        assertNull(server.verifier.verify(third.access), "the live token of the family dies with the reuse")
        assertEquals("invalid_grant", refresh(third.refresh, clientId).json()["error"]?.jsonPrimitive?.content)
        // A lost response and a retry look exactly like this; the grant is the sweep's, not the reuse's.
        assertNotNull(harness.store.links.find(PUBKY), "a reuse revoked the grant outright")
        assertTrue(ring.revoked.isEmpty())
        clock.advance(Duration.ofMinutes(61))
        assertEquals(1, server.unlinker.sweepExpired(), "a link with no live family is swept within the hour")
        assertEquals(listOf(SESSION_SECRET), ring.revoked)
    }

    @Test
    fun `an expired access token is refused and a refresh brings a new one`() = oauthTest {
        val clientId = registerClient()
        val tokens = tokensFor(clientId)
        clock.advance(Duration.ofMinutes(61))
        assertNull(server.verifier.verify(tokens.access))
        val renewed = refresh(tokens.refresh, clientId).json().tokens()
        assertNotNull(server.verifier.verify(renewed.access))
    }

    @Test
    fun `a refresh token past the inactivity bound unlinks the account`() = oauthTest {
        val clientId = registerClient()
        val tokens = tokensFor(clientId)
        clock.advance(Duration.ofDays(91))
        assertEquals("invalid_grant", refresh(tokens.refresh, clientId).json()["error"]?.jsonPrimitive?.content)
        assertNull(harness.store.links.find(PUBKY))
        assertEquals(listOf(SESSION_SECRET), ring.revoked)
    }

    @Test
    fun `a token is bound to the client it was issued to`() = oauthTest {
        val clientId = registerClient()
        val other = registerClient("Other")
        val tokens = tokensFor(clientId)
        assertEquals("invalid_grant", refresh(tokens.refresh, other).json()["error"]?.jsonPrimitive?.content)
        assertNotNull(server.verifier.verify(tokens.access), "a refused refresh from a stranger is not a reuse")
    }

    @Test
    fun `the verifier refuses garbage, an unknown token and a wrong audience`() = oauthTest {
        assertNull(server.verifier.verify("lk_at_" + "A".repeat(43)))
        assertNull(server.verifier.verify("not a token"))
        val clientId = registerClient()
        val tokens = tokensFor(clientId)
        harness.store.db.transaction { it.createStatement().use { s -> s.execute("UPDATE access_tokens SET audience = 'https://x/mcp'") } }
        assertNull(server.verifier.verify(tokens.access))
    }

    @Test
    fun `revoke ends a refresh token's family, and the link when it was the last`() = oauthTest {
        val clientId = registerClient()
        val tokens = tokensFor(clientId)
        val revoked = revoke(tokens.refresh)
        assertEquals(HttpStatusCode.OK, revoked.status)
        assertNull(server.verifier.verify(tokens.access))
        assertNull(harness.store.links.find(PUBKY))
        assertEquals(listOf(SESSION_SECRET), ring.revoked)
        assertEquals(HttpStatusCode.OK, revoke("lk_rt_unknown").status, "RFC 7009: unknown tokens are not an error")
    }

    @Test
    fun `revoking an access token leaves the family alive`() = oauthTest {
        val clientId = registerClient()
        val tokens = tokensFor(clientId)
        revoke(tokens.access)
        assertNull(server.verifier.verify(tokens.access))
        assertNotNull(harness.store.links.find(PUBKY))
        assertEquals(HttpStatusCode.OK, refresh(tokens.refresh, clientId).status)
    }

    @Test
    fun `the token endpoint answers RFC 6749 errors`() = oauthTest {
        val clientId = registerClient()
        val missing = exchange("", clientId)
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("invalid_request", missing.json()["error"]?.jsonPrimitive?.content)
        val unsupported = http.post("/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("grant_type=password&username=a&password=b")
        }
        assertEquals("unsupported_grant_type", unsupported.json()["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the status endpoint reports a denied or vanished flow and never leaks the Ring URL`() = oauthTest {
        val clientId = registerClient()
        val txn = authorizeTxn(clientId)
        ring.fail()
        val failed = status(txn)
        assertEquals("failed", failed["status"]?.jsonPrimitive?.content)
        assertFalse("pubkyauth" in failed.toString())
        assertEquals("expired", status(txn)["status"]?.jsonPrimitive?.content)
        assertEquals(HttpStatusCode.BadRequest, http.get("/authorize/status").status)
    }

    /** The one moment a live grant exists that no row names: it must not outlive the failure. */
    @Test
    fun `an approval the server cannot store is revoked on the homeserver and reported failed`() = oauthTest {
        val clientId = registerClient()
        val txn = authorizeTxn(clientId)
        ring.approve()
        harness.store.db.transaction { it.createStatement().use { s -> s.execute("DROP TABLE links") } }
        val reply = status(txn)
        assertEquals("failed", reply["status"]?.jsonPrimitive?.content, reply.toString())
        assertNull(reply["redirect"])
        assertEquals(listOf(SESSION_SECRET), ring.revoked, "the grant nothing references was left live")
    }

    @Test
    fun `a pending login expires with the relay window and is cancelled`() = oauthTest {
        val clientId = registerClient()
        val txn = authorizeTxn(clientId)
        clock.advance(Duration.ofMinutes(6))
        assertEquals("expired", status(txn)["status"]?.jsonPrimitive?.content)
        assertEquals(ring.started, ring.cancelled)
        ring.approve()
        assertEquals("expired", status(txn)["status"]?.jsonPrimitive?.content, "a late approval is not accepted")
        assertNull(harness.store.links.find(PUBKY))
    }

    @Test
    fun `pending logins are capped per IP and globally`() = oauthTest(OAuthLimits(pending = PendingLoginCaps(perIp = 1, global = 2))) {
        val clientId = registerClient()
        assertEquals(HttpStatusCode.OK, authorize(clientId, ip = "203.0.113.1").status)
        assertEquals(HttpStatusCode.TooManyRequests, authorize(clientId, ip = "203.0.113.1").status)
        assertEquals(HttpStatusCode.OK, authorize(clientId, ip = "203.0.113.2").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, authorize(clientId, ip = "203.0.113.3").status)
        assertEquals(2, ring.started.size)
        clock.advance(Duration.ofMinutes(6))
        assertEquals(HttpStatusCode.OK, authorize(clientId, ip = "203.0.113.3").status, "expired flows free their slots")
        assertEquals(2, ring.cancelled.size)
    }

    @Test
    fun `an embedded request never starts a flow, a navigation does`() = oauthTest {
        val clientId = registerClient()
        val image = authorize(clientId, headers = mapOf("Sec-Fetch-Mode" to "no-cors", "Sec-Fetch-Dest" to "image"))
        assertEquals(HttpStatusCode.BadRequest, image.status)
        val frame = authorize(clientId, headers = mapOf("Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Dest" to "iframe"))
        assertEquals(HttpStatusCode.BadRequest, frame.status)
        assertTrue(ring.started.isEmpty(), "a flow was started for something that was not a page")
        val page = authorize(clientId, headers = mapOf("Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Dest" to "document"))
        assertEquals(HttpStatusCode.OK, page.status)
    }

    @Test
    fun `a login nobody polls is dropped before the window, a polled one lives to it`() = oauthTest {
        val clientId = registerClient()
        authorizeTxn(clientId)
        clock.advance(Duration.ofMinutes(3))
        server.pending.sweep()
        assertEquals(0, server.pending.size, "an unpolled flow held its slot")
        assertEquals(ring.started, ring.cancelled)

        val polled = authorizeTxn(clientId)
        repeat(3) {
            clock.advance(Duration.ofSeconds(90))
            assertEquals("pending", status(polled)["status"]?.jsonPrimitive?.content, "polling keeps a flow alive")
        }
        clock.advance(Duration.ofSeconds(90))
        assertEquals("expired", status(polled)["status"]?.jsonPrimitive?.content, "but never past the relay window")
    }

    @Test
    fun `IPv6 clients are capped by their 64`() = oauthTest(OAuthLimits(pending = PendingLoginCaps(perIp = 1, global = 10))) {
        val clientId = registerClient()
        assertEquals(HttpStatusCode.OK, authorize(clientId, ip = "2001:db8:1:2::1").status)
        assertEquals(HttpStatusCode.TooManyRequests, authorize(clientId, ip = "2001:db8:1:2:ffff::9").status)
        assertEquals(HttpStatusCode.OK, authorize(clientId, ip = "2001:db8:1:3::1").status)
    }

    /** The relay round trip used to run under the one lock every poll takes. */
    @Test
    fun `a slow relay stalls nobody else's poll, and the slot it reserved is counted`() = oauthTest(
        OAuthLimits(pending = PendingLoginCaps(perIp = 1, global = 10)),
    ) {
        val clientId = registerClient()
        val gate = CompletableDeferred<Unit>()
        ring.startGate = gate
        coroutineScope {
            val slow = async { authorize(clientId, ip = "203.0.113.1") }
            while (server.pending.reservedSlots() == 0) yield()
            ring.startGate = null
            val poll = withTimeout(5_000) { status("no-such-txn") }
            assertEquals("expired", poll["status"]?.jsonPrimitive?.content)
            assertEquals(HttpStatusCode.TooManyRequests, authorize(clientId, ip = "203.0.113.1").status, "a reserved slot counts")
            gate.complete(Unit)
            assertEquals(HttpStatusCode.OK, slow.await().status)
        }
        assertEquals(1, server.pending.size)
    }

    @Test
    fun `a Ring that cannot start a flow is a 502, not a page`() = oauthTest {
        val clientId = registerClient()
        ring.startFailure = IllegalStateException("relay down")
        assertEquals(HttpStatusCode.BadGateway, authorize(clientId).status)
    }

    @Test
    fun `requests over the per-IP budget get 429 with Retry-After`() = oauthTest(
        OAuthLimits(requestRate = RateLimitPolicy(burst = 2, perSecond = 0.001)),
    ) {
        assertEquals(HttpStatusCode.Created, register().status)
        assertEquals(HttpStatusCode.Created, register().status)
        val limited = register()
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        assertEquals(HttpStatusCode.OK, http.get("/.well-known/oauth-authorization-server").status, "metadata is not limited")
    }

    @Test
    fun `re-linking stores the new credential and revokes the old grant`() = oauthTest {
        val clientId = registerClient()
        val first = tokensFor(clientId)
        val newSecret = SESSION_SECRET.replace("AAAA", "BBBB")
        val code = codeFor(clientId, verifier = VERIFIER + "-again", secret = newSecret)
        val second = exchange(code, clientId, verifier = VERIFIER + "-again").json().tokens()
        assertNotNull(server.verifier.verify(second.access))
        assertNotNull(server.verifier.verify(first.access), "the earlier connection keeps working on the new credential")
        assertEquals(listOf(SESSION_SECRET), ring.revoked, "the previous grant is revoked once the new one is stored")
        assertEquals(newSecret, server.sessionSecrets.sessionSecretFor(PUBKY))
    }
}
