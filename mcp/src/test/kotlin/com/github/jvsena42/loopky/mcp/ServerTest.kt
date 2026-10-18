package com.github.jvsena42.loopky.mcp

import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.mcp.auth.OAuthClient
import com.github.jvsena42.loopky.mcp.auth.OAuthHarness
import com.github.jvsena42.loopky.mcp.auth.PUBKY
import com.github.jvsena42.loopky.mcp.auth.json
import com.github.jvsena42.loopky.mcp.auth.tokens
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The composed server: the OAuth half issuing a token that the MCP half accepts, and a tool call
 * landing on the graph opened from the credential that sign-in stored. Each half has its own suite;
 * this is the one test that the two agree on the store, the vault and the principal between them.
 */
class ServerTest {

    @Test
    fun `a token from the OAuth dance runs a tool on the signed-in user's graph`() = testApplication {
        val harness = OAuthHarness.create()
        val factory = FakeTenantFactory { pubky, secret ->
            FakeTenant(secret, sessionFor(pubky, secret), decks = FakeDeckRepository(testDeck(), owned = listOf(ownedDeck(pubky))))
        }
        try {
            application {
                loopkyMcp(
                    ServerParts(
                        oauth = harness.deps(),
                        tenantFactory = factory,
                        scratchDir = Files.createTempDirectory("mcp-server").resolve("inline"),
                    ),
                )
            }
            val http = createClient { followRedirects = false }
            assertEquals("ok", http.get("/healthz").bodyAsText())
            // Claude and the connector directory show the icon at the server's own URL (#536).
            listOf("/favicon.ico", "/favicon.png").forEach { path ->
                val icon = http.get(path)
                assertEquals(HttpStatusCode.OK, icon.status, path)
                assertEquals(ContentType.Image.PNG, icon.contentType()?.withoutParameters(), path)
                val bytes = icon.readRawBytes()
                assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), bytes.take(4).map { it.toInt() and 0xFF }, "$path is a PNG")
            }

            val oauth = OAuthClient(harness, http)
            val clientId = oauth.registerClient()
            val code = oauth.codeFor(clientId, secret = "secret-$PUBKY")
            val token = oauth.exchange(code, clientId).json().tokens().access

            suspend fun rpc(body: String, session: String? = null): HttpResponse = http.post(MCP_PATH) {
                header(HttpHeaders.Authorization, "Bearer $token")
                session?.let { header("Mcp-Session-Id", it) }
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val initialized = rpc(INITIALIZE)
            assertEquals(HttpStatusCode.OK, initialized.status, initialized.bodyAsText())
            val session = assertNotNull(initialized.headers["Mcp-Session-Id"])
            rpc(INITIALIZED, session)

            val called = Json.parseToJsonElement(rpc(DECK_LIST, session).bodyAsText()).jsonObject
            val envelope = called["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            val deck = envelope["data"]!!.jsonObject["decks"]!!.jsonArray.single().jsonObject
            assertEquals("deck-$PUBKY", deck["id"]!!.jsonPrimitive.content)
            assertEquals("secret-$PUBKY", (factory.opened.single() as FakeTenant).secret)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `health fails once the store cannot be read`() = testApplication {
        val harness = OAuthHarness.create()
        try {
            application {
                loopkyMcp(
                    ServerParts(
                        oauth = harness.deps(),
                        tenantFactory = FakeTenantFactory(),
                        scratchDir = Files.createTempDirectory("mcp-health").resolve("inline"),
                        upkeep = Upkeep(onWedged = {}),
                    ),
                )
            }
            assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
            harness.store.close()
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/healthz").status)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a store that stays unreadable makes the server exit, so the container restarts`() = testApplication {
        val harness = OAuthHarness.create()
        val wedged = CompletableDeferred<Unit>()
        try {
            application {
                loopkyMcp(
                    ServerParts(
                        oauth = harness.deps(),
                        tenantFactory = FakeTenantFactory(),
                        scratchDir = Files.createTempDirectory("mcp-watchdog").resolve("inline"),
                        upkeep = Upkeep(watchdogEvery = 50.milliseconds, onWedged = { wedged.complete(Unit) }),
                    ),
                )
            }
            startApplication()
            harness.store.close()
            withTimeout(10.seconds) { wedged.await() }
        } finally {
            harness.close()
        }
    }

    private companion object {
        const val INITIALIZE = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
            """"capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
        const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        const val DECK_LIST = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"deck_list","arguments":{}}}"""
    }
}
