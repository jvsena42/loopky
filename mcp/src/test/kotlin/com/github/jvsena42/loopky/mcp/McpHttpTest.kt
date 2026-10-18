package com.github.jvsena42.loopky.mcp

import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.mcp.security.RateLimitPolicy
import com.github.jvsena42.loopky.mcp.tenant.TenantLimits
import com.github.jvsena42.loopky.mcp.tenant.TenantPool
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `tools/list` and `tools/call` over Streamable HTTP, and the gates in front of them. */
class McpHttpTest {

    private val verifier = FakeVerifier(
        mapOf(
            "token-alice" to Principal("alice", "claude"),
            "token-bob" to Principal("bob", "claude"),
        ),
    )
    private val secrets = FakeSecrets(mapOf("alice" to "secret-alice", "bob" to "secret-bob"))
    private val factory = FakeTenantFactory { pubky, secret ->
        FakeTenant(secret, sessionFor(pubky, secret), decks = FakeDeckRepository(testDeck(), owned = listOf(ownedDeck(pubky))))
    }
    private val pool = TenantPool(factory, secrets, TenantLimits())

    private fun deps(limits: HttpLimits = HttpLimits(), verifier: AccessTokenVerifier = this.verifier) = McpDeps(
        environment = PubkyEnvironment.Staging,
        publicUrl = "https://mcp.staging.loopky.app",
        verifier = verifier,
        originPolicy = OriginPolicy.allowing("https://claude.ai"),
        tenants = pool,
        scratchDir = Files.createTempDirectory("mcp-http").resolve("inline"),
        instructions = "Rules.",
        limits = limits,
    )

    private fun serving(limits: HttpLimits = HttpLimits(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(XForwardedHeaders)
            installMcp(deps(limits))
        }
        block()
    }

    private suspend fun HttpClient.rpc(
        body: String,
        token: String? = "token-alice",
        session: String? = null,
        origin: String? = null,
    ): HttpResponse = post(MCP_PATH) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        session?.let { header("Mcp-Session-Id", it) }
        origin?.let { header(HttpHeaders.Origin, it) }
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.initialize(token: String = "token-alice"): String {
        val response = rpc(INITIALIZE, token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val session = assertNotNull(response.headers["Mcp-Session-Id"], "no session id on the initialize response")
        assertEquals(HttpStatusCode.Accepted, rpc(INITIALIZED, token, session).status)
        return session
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `tools are listed and called over Streamable HTTP`() = serving {
        val session = client.initialize()

        val listed = client.rpc(LIST_TOOLS, session = session).json()
        val tools = listed["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(12, tools.size)
        assertContains(tools, "deck_create")
        assertContains(tools, "card_remove")

        val called = client.rpc(call("deck_list", "{}", id = 3), session = session).json()
        val result = called["result"]!!.jsonObject
        assertNull(result["isError"]?.jsonPrimitive?.boolean?.takeIf { it }, called.toString())
        val envelope = result["structuredContent"]!!.jsonObject
        assertEquals(true, envelope["ok"]!!.jsonPrimitive.boolean)
        assertEquals("deck list", envelope["command"]!!.jsonPrimitive.content)
        assertEquals("staging", envelope["environment"]!!.jsonPrimitive.content)
        val decks = envelope["data"]!!.jsonObject["decks"]!!.jsonArray
        assertEquals("deck-alice", decks.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the initialize result carries the instructions`() = serving {
        val response = client.rpc(INITIALIZE)
        assertEquals("Rules.", response.json()["result"]!!.jsonObject["instructions"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no bearer is 401 with the protected-resource challenge`() = serving {
        val response = client.rpc(INITIALIZE, token = null)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(
            "Bearer resource_metadata=\"https://mcp.staging.loopky.app/.well-known/oauth-protected-resource\"",
            response.headers[HttpHeaders.WWWAuthenticate],
        )
        assertEquals(HttpStatusCode.Unauthorized, client.rpc(INITIALIZE, token = "token-mallory").status)
    }

    @Test
    fun `failed bearers are bounded per address, and a valid bearer from that address never is`() = serving(
        HttpLimits(failedAuthRate = RateLimitPolicy(burst = 2, perSecond = 0.0)),
    ) {
        // Discovery carries no token: never charged, however often.
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.rpc(INITIALIZE, token = null).status) }
        assertEquals(HttpStatusCode.Unauthorized, client.rpc(INITIALIZE, token = "token-mallory").status)
        assertEquals(HttpStatusCode.Unauthorized, client.rpc(INITIALIZE, token = "token-eve").status)
        val limited = client.rpc(INITIALIZE, token = "token-trudy")
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        // Same address, spent bucket: a bearer that verifies still gets through.
        assertEquals(HttpStatusCode.OK, client.rpc(INITIALIZE).status)
    }

    @Test
    fun `an expired token is always the 401 that says refresh, however many share its address`() = testApplication {
        // Every Claude user's expired token arrives from the same few Anthropic addresses (#538).
        val expired = (1..50).map { "expired-$it" }.toSet()
        application {
            installMcp(
                deps(
                    limits = HttpLimits(failedAuthRate = RateLimitPolicy(burst = 2, perSecond = 0.0)),
                    verifier = FakeVerifier(mapOf("token-alice" to Principal("alice", "claude")), lapsed = expired),
                ),
            )
        }
        repeat(3) { client.rpc(INITIALIZE, token = "made-up-$it") }
        expired.forEach { token ->
            val response = client.rpc(INITIALIZE, token = token)
            assertEquals(HttpStatusCode.Unauthorized, response.status, token)
            assertNotNull(response.headers[HttpHeaders.WWWAuthenticate], token)
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.rpc(INITIALIZE, token = "made-up-again").status)
    }

    @Test
    fun `a token seen failing is refused again without a lookup`() = testApplication {
        val counting = CountingVerifier(verifier)
        application { installMcp(deps(verifier = counting)) }
        repeat(3) { assertEquals(HttpStatusCode.Unauthorized, client.rpc(INITIALIZE, token = "token-mallory").status) }
        assertEquals(1, counting.calls)
    }

    @Test
    fun `an unauthenticated stream is 401 before any event stream starts`() = serving {
        val response = client.get(MCP_PATH) { header(HttpHeaders.Accept, "text/event-stream") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNotNull(response.headers[HttpHeaders.WWWAuthenticate])
    }

    /** Inside `sse {}` the 200 is already out; the refusal has to happen in the gate to be a refusal at all. */
    @Test
    fun `a stream for an unknown or foreign session is 404 before any event stream starts`() = serving {
        val session = client.initialize("token-alice")
        suspend fun stream(token: String, id: String?) = client.get(MCP_PATH) {
            header(HttpHeaders.Authorization, "Bearer $token")
            id?.let { header("Mcp-Session-Id", it) }
            header(HttpHeaders.Accept, "text/event-stream")
        }
        val foreign = stream("token-bob", session)
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals(ContentType.Application.Json.contentType, foreign.contentType()?.contentType)
        assertEquals(ContentType.Application.Json.contentSubtype, foreign.contentType()?.contentSubtype)
        assertEquals(HttpStatusCode.NotFound, stream("token-alice", "not-a-session").status)
        assertEquals(HttpStatusCode.BadRequest, stream("token-alice", null).status)
        assertEquals(HttpStatusCode.NotFound, client.delete(MCP_PATH) {
            header(HttpHeaders.Authorization, "Bearer token-bob")
            header("Mcp-Session-Id", session)
        }.status)
    }

    @Test
    fun `a disallowed origin is 403, an allowed or absent one passes`() = serving {
        assertEquals(HttpStatusCode.Forbidden, client.rpc(INITIALIZE, origin = "https://evil.example").status)
        assertEquals(HttpStatusCode.Forbidden, client.rpc(INITIALIZE, origin = "https://claude.ai.evil.example").status)
        assertEquals(HttpStatusCode.OK, client.rpc(INITIALIZE, origin = "https://claude.ai").status)
        val normalised = client.rpc(INITIALIZE, origin = "https://Claude.ai:443/")
        assertEquals(HttpStatusCode.OK, normalised.status, "one normalisation, the validator's")
    }

    @Test
    fun `a session created for one principal is not found for another`() = serving {
        val session = client.initialize("token-alice")
        val stolen = client.rpc(call("deck_list", "{}"), token = "token-bob", session = session)
        assertEquals(HttpStatusCode.NotFound, stolen.status)
        // And bob's own session serves bob's decks, never alice's.
        val own = client.initialize("token-bob")
        val envelope = client.rpc(call("deck_list", "{}"), token = "token-bob", session = own).json()["result"]!!
            .jsonObject["structuredContent"]!!.jsonObject
        assertEquals("deck-bob", envelope["data"]!!.jsonObject["decks"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        // Alice's session still answers alice.
        assertEquals(HttpStatusCode.OK, client.rpc(call("deck_list", "{}"), session = session).status)
    }

    @Test
    fun `a revoked credential answers the call once, then 401 with the challenge`() = serving {
        val session = client.initialize()
        secrets.secrets["alice"] = "secret-alice"
        // The tenant opened for alice refuses its session on the first read.
        pool.close()
        val refusing = TenantPool(
            FakeTenantFactory { pubky, secret ->
                FakeTenant(
                    secret,
                    sessionFor(pubky, secret),
                    decks = FakeDeckRepository(testDeck("d1"), readFails = PubkyError("Session rejected: 401 Unauthorized")),
                )
            },
            secrets,
            TenantLimits(),
        )
        testApplication {
            application {
                installMcp(
                    McpDeps(
                        environment = PubkyEnvironment.Staging,
                        publicUrl = "https://mcp.example",
                        verifier = verifier,
                        originPolicy = OriginPolicy.allowing(),
                        tenants = refusing,
                        scratchDir = Files.createTempDirectory("mcp-http-revoked"),
                        instructions = "R",
                    ),
                )
            }
            val own = client.initialize()
            val called = client.rpc(call("deck_show", """{"deck_id":"d1"}"""), session = own).json()
            val envelope = called["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            assertEquals("session_expired", envelope["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals(true, envelope["error"]!!.jsonObject["reconnect"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("alice"), secrets.revoked)

            val next = client.rpc(call("deck_list", "{}"), session = own)
            assertEquals(HttpStatusCode.Unauthorized, next.status)
            val challenge = assertNotNull(next.headers[HttpHeaders.WWWAuthenticate])
            assertContains(challenge, "resource_metadata=\"https://mcp.example/.well-known/oauth-protected-resource\"")
            assertContains(challenge, "invalid_token")
        }
        assertTrue(session.isNotEmpty())
    }

    @Test
    fun `a principal churning initialize evicts its own sessions, never another's`() = serving(
        HttpLimits(maxSessions = 4, maxSessionsPerPrincipal = 2),
    ) {
        val alice = listOf(client.initialize("token-alice"), client.initialize("token-alice"))
        val bob = (1..5).map { client.initialize("token-bob") }
        alice.forEach { assertEquals(HttpStatusCode.OK, client.rpc(call("deck_list", "{}"), session = it).status) }
        bob.dropLast(2).forEach { assertEquals(HttpStatusCode.NotFound, client.rpc(call("deck_list", "{}"), "token-bob", it).status) }
        bob.takeLast(2).forEach { assertEquals(HttpStatusCode.OK, client.rpc(call("deck_list", "{}"), "token-bob", it).status) }
        // A third of alice's own pushes her oldest out and nothing of bob's.
        val third = client.initialize("token-alice")
        assertEquals(HttpStatusCode.NotFound, client.rpc(call("deck_list", "{}"), session = alice.first()).status)
        assertEquals(HttpStatusCode.OK, client.rpc(call("deck_list", "{}"), session = third).status)
        bob.takeLast(2).forEach { assertEquals(HttpStatusCode.OK, client.rpc(call("deck_list", "{}"), "token-bob", it).status) }
    }

    @Test
    fun `a body past the limit is 413`() = serving(HttpLimits(maxBodyBytes = 256)) {
        val response = client.rpc(call("deck_list", """{"x":"${"a".repeat(300)}"}"""))
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `a request without a session after initialize is refused by the transport`() = serving {
        client.initialize()
        assertEquals(HttpStatusCode.NotFound, client.rpc(call("deck_list", "{}"), session = "not-a-session").status)
    }

    private fun call(tool: String, arguments: String, id: Int = 2) =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""

    private companion object {
        const val INITIALIZE = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
            """"capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
        const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        const val LIST_TOOLS = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
    }
}

private class CountingVerifier(private val delegate: AccessTokenVerifier) : AccessTokenVerifier {
    @Volatile var calls = 0

    override suspend fun check(accessToken: String): TokenCheck {
        calls++
        return delegate.check(accessToken)
    }
}
