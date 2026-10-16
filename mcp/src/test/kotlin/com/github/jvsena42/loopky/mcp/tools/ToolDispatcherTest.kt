package com.github.jvsena42.loopky.mcp.tools

import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.SCHEMA_VERSION
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.mcp.FakeSecrets
import com.github.jvsena42.loopky.mcp.FakeTenant
import com.github.jvsena42.loopky.mcp.FakeTenantFactory
import com.github.jvsena42.loopky.mcp.Principal
import com.github.jvsena42.loopky.mcp.ownedDeck
import com.github.jvsena42.loopky.mcp.security.RateLimitPolicy
import com.github.jvsena42.loopky.mcp.security.UserLimiter
import com.github.jvsena42.loopky.mcp.security.UserLimits
import com.github.jvsena42.loopky.mcp.sessionFor
import com.github.jvsena42.loopky.mcp.tenant.Tenant
import com.github.jvsena42.loopky.mcp.tenant.TenantLimits
import com.github.jvsena42.loopky.mcp.tenant.TenantPool
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolDispatcherTest {

    private val alice = Principal(pubky = "alice", clientId = "claude")
    private val secrets = FakeSecrets(mapOf("alice" to "secret-alice"))
    private val scratch = InlineScratch(Files.createTempDirectory("dispatcher-test").resolve("inline"))

    private fun dispatcher(build: (pubky: String, secret: String) -> Tenant): Pair<ToolDispatcher, FakeTenantFactory> {
        val factory = FakeTenantFactory(build)
        val pool = TenantPool(factory, secrets, TenantLimits())
        return ToolDispatcher(PubkyEnvironment.Staging, pool, scratch) to factory
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun tenantWith(decks: FakeDeckRepository, cards: FakeCardRepository = FakeCardRepository()) =
        { pubky: String, secret: String -> FakeTenant(secret, sessionFor(pubky, secret), decks = decks, cards = cards) }

    @Test
    fun `a result is the CLI's envelope, as text and as structured content`() = runBlocking {
        val (dispatcher, _) = dispatcher(tenantWith(FakeDeckRepository(testDeck(), owned = listOf(ownedDeck("alice")))))
        val result = dispatcher.call(alice, "deck_list", args("{}"))

        assertEquals(false, result.isError)
        val text = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
        assertEquals(text, result.structuredContent)
        assertEquals(SCHEMA_VERSION, text["schema"]!!.jsonPrimitive.int)
        assertEquals(true, text["ok"]!!.jsonPrimitive.boolean)
        assertEquals("deck list", text["command"]!!.jsonPrimitive.content)
        assertEquals("staging", text["environment"]!!.jsonPrimitive.content)
        assertEquals(PubkyEnvironment.Staging.nexusBaseUrl, text["indexer"]!!.jsonPrimitive.content)
        assertTrue("update_available" in text)
        assertEquals("deck-alice", text["data"]!!.jsonObject["decks"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `writes are charged to the user's bucket and budget, dry runs and reads are not`() = runBlocking {
        val limiter =
            UserLimiter(UserLimits(writes = RateLimitPolicy.perMinute(count = 1, burst = 1), dailyByteBudget = 16))
        val pool =
            TenantPool(FakeTenantFactory(tenantWith(FakeDeckRepository(testDeck("d1")))), secrets, TenantLimits())
        val dispatcher = ToolDispatcher(PubkyEnvironment.Staging, pool, scratch, userLimiter = limiter)
        val add = """{"deck_id":"d1","front":"hola","back":"hello"}"""

        assertEquals(false, dispatcher.call(alice, "card_add", args(add)).isError)
        assertEquals(false, dispatcher.call(alice, "deck_list", args("{}")).isError)
        assertEquals(false, dispatcher.call(alice, "card_add", args(add.dropLast(1) + ""","dry_run":true}""")).isError)

        val limited = dispatcher.call(alice, "card_add", args(add)).envelope()
        assertEquals("rate_limited", limited["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val roomy = UserLimiter(UserLimits(dailyByteBudget = 16))
        val budgeted = ToolDispatcher(PubkyEnvironment.Staging, pool, scratch, userLimiter = roomy)
        val bulk = budgeted.call(
            alice,
            "card_add",
            args("""{"deck_id":"d1","cards":[{"front":"a long enough front","back":"and a back"}]}"""),
        ).envelope()
        assertEquals("rate_limited", bulk["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `whoami answers from the principal without opening a graph`() = runBlocking {
        val (dispatcher, factory) = dispatcher(tenantWith(FakeDeckRepository(testDeck())))
        val data = dispatcher.call(alice, "whoami", args("{}")).envelope()["data"]!!.jsonObject
        assertEquals("alice", data["pubky"]!!.jsonPrimitive.content)
        assertEquals("staging", data["environment"]!!.jsonPrimitive.content)
        assertEquals(PubkyEnvironment.Staging.nexusBaseUrl, data["indexer"]!!.jsonPrimitive.content)
        assertEquals(0, factory.opens.get())
    }

    @Test
    fun `inline cards reach the command as a file that is gone afterwards`() = runBlocking {
        val decks = FakeDeckRepository(testDeck("d1"))
        val (dispatcher, _) = dispatcher(tenantWith(decks))
        val envelope = dispatcher.call(
            alice,
            "card_add",
            args("""{"deck_id":"d1","cards":[{"front":"hola","back":"hello"},{"front":"adiós","back":"bye"}]}"""),
        ).envelope()

        assertEquals(true, envelope["ok"]!!.jsonPrimitive.boolean, envelope.toString())
        assertEquals(2, envelope["data"]!!.jsonObject["written"]!!.jsonPrimitive.int)
        assertEquals(listOf("hola", "adiós"), decks.appended.map { it.front.text })
        assertEquals(emptyList(), scratch.root.listDirectoryEntries(), "the call's directory was left behind")
    }

    /** The real parser, over text that never touched the caller's filesystem. */
    @Test
    fun `inline import goes through the app's own parser and publishes`() = runBlocking {
        val decks = FakeDeckRepository(testDeck())
        val (dispatcher, _) = dispatcher(tenantWith(decks))
        val envelope = dispatcher.call(
            alice,
            "import",
            args("""{"title":"Greetings","text":"hola\thello\nadiós\tbye\n","tag":["Español"]}"""),
        ).envelope()

        assertEquals(true, envelope["ok"]!!.jsonPrimitive.boolean, envelope.toString())
        val data = envelope["data"]!!.jsonObject
        assertEquals(2, data["cards_written"]!!.jsonPrimitive.int)
        assertEquals("tab", data["separator"]!!.jsonPrimitive.content)
        assertEquals(listOf("hola", "adiós"), decks.published.map { it.front.text })
        assertEquals("espanol", data["deck"]!!.jsonObject["tags"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(emptyList(), scratch.root.listDirectoryEntries())
    }

    @Test
    fun `a dry run writes nothing and reports the preview`() = runBlocking {
        val decks = FakeDeckRepository(testDeck())
        val (dispatcher, _) = dispatcher(tenantWith(decks))
        val envelope = dispatcher.call(alice, "import", args("""{"text":"a\tb\nc\td\ne\tf","dry_run":true}""")).envelope()
        assertEquals(true, envelope["ok"]!!.jsonPrimitive.boolean, envelope.toString())
        assertEquals(3, envelope["data"]!!.jsonObject["cards"]!!.jsonPrimitive.int)
        assertEquals(emptyList(), decks.published)
        assertEquals("inline", envelope["data"]!!.jsonObject["source"]!!.jsonPrimitive.content)
    }

    /** The commands name the file they read; the server's directory layout is not the caller's to learn. */
    @Test
    fun `the scratch path never reaches the caller, in a result or in a message`() = runBlocking {
        val (dispatcher, _) = dispatcher(tenantWith(FakeDeckRepository(testDeck())))
        val empty = dispatcher.call(alice, "import", args("""{"text":"only a front\n","dry_run":true}""")).envelope()
        assertEquals("Nothing importable in inline.", empty["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        val blank = dispatcher.call(alice, "card_add", args("""{"deck_id":"d1","text":"# only a comment\n"}""")).envelope()
        assertEquals("inline held no cards.", blank["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertFalse(scratch.root.toString() in empty.toString() + blank.toString())
    }

    @Test
    fun `a CLI refusal is the failure envelope with the exit code's name, and no stack trace`() = runBlocking {
        val (dispatcher, _) = dispatcher(tenantWith(FakeDeckRepository(testDeck("d1"))))
        val result = dispatcher.call(alice, "card_add", args("""{"deck_id":"d1","front":"only a front"}"""))
        assertEquals(true, result.isError)
        val error = result.envelope()["error"]!!.jsonObject
        assertEquals("bad_input", error["code"]!!.jsonPrimitive.content)
        assertEquals(9, error["exit"]!!.jsonPrimitive.int)
        assertEquals(false, error["reconnect"]!!.jsonPrimitive.boolean)
        assertFalse("\tat " in result.envelope().toString())
    }

    @Test
    fun `an unknown tool and an unknown input are usage errors`() = runBlocking {
        val (dispatcher, factory) = dispatcher(tenantWith(FakeDeckRepository(testDeck())))
        assertEquals("usage", dispatcher.call(alice, "deck_delete", args("{}")).errorCode())
        assertEquals("usage", dispatcher.call(alice, "deck_show", args("""{"deck_id":"d1","from_file":"/x"}""")).errorCode())
        assertEquals(0, factory.opens.get(), "nothing was opened for a call that could not run")
    }

    @Test
    fun `a private picture address is refused before the graph is touched`() = runBlocking {
        val (dispatcher, factory) = dispatcher(tenantWith(FakeDeckRepository(testDeck("d1"))))
        val result = dispatcher.call(
            alice,
            "card_add",
            args("""{"deck_id":"d1","front":"a","back":"b","back_image":"https://10.0.0.5/x.png"}"""),
        )
        assertEquals("bad_input", result.errorCode())
        assertContains(result.envelope()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content, "10.0.0.5")
        assertEquals(0, factory.opens.get())
    }

    @Test
    fun `a refused session is reported revoked and the caller told to reconnect`() = runBlocking {
        val refused = PubkyError("Session rejected: 401 Unauthorized")
        val factory = FakeTenantFactory { pubky, secret ->
            FakeTenant(secret, sessionFor(pubky, secret), decks = FakeDeckRepository(testDeck("d1"), readFails = refused))
        }
        val pool = TenantPool(factory, secrets, TenantLimits())
        val dispatcher = ToolDispatcher(PubkyEnvironment.Staging, pool, scratch)

        val result = dispatcher.call(alice, "deck_show", args("""{"deck_id":"d1"}"""))

        assertEquals(true, result.isError)
        val error = result.envelope()["error"]!!.jsonObject
        assertEquals("session_expired", error["code"]!!.jsonPrimitive.content)
        assertEquals(true, error["reconnect"]!!.jsonPrimitive.boolean)
        assertContains(error["message"]!!.jsonPrimitive.content, "reconnect")
        assertEquals(listOf("alice"), secrets.revoked)
        assertTrue(pool.isRevoked("alice"))
        assertTrue((factory.opened.single() as FakeTenant).closed)
    }

    /** The exit table folds this into `session_expired`; here it must stay a retry, never an unlink. */
    @Test
    fun `a homeserver that cannot be reached for the session check is a network failure, not a revoke`() = runBlocking {
        val unreachable = PubkyError("Failed to import session: Request failed: HTTP transport error")
        val factory = FakeTenantFactory { pubky, secret ->
            FakeTenant(secret, sessionFor(pubky, secret), decks = FakeDeckRepository(testDeck("d1"), readFails = unreachable))
        }
        val pool = TenantPool(factory, secrets, TenantLimits())
        val dispatcher = ToolDispatcher(PubkyEnvironment.Staging, pool, scratch)

        val result = dispatcher.call(alice, "deck_show", args("""{"deck_id":"d1"}"""))

        assertEquals(true, result.isError)
        val error = result.envelope()["error"]!!.jsonObject
        assertEquals("network", error["code"]!!.jsonPrimitive.content)
        assertEquals(false, error["reconnect"]!!.jsonPrimitive.boolean)
        assertContains(error["message"]!!.jsonPrimitive.content, "still linked")
        assertEquals(emptyList(), secrets.revoked, "a transient failure unlinked the user")
        assertFalse(pool.isRevoked("alice"))
        assertFalse((factory.opened.single() as FakeTenant).closed, "the graph was dropped for a failure a retry fixes")
    }

    @Test
    fun `a refused session on a batch write is still reported revoked`() = runBlocking {
        val decks = FakeDeckRepository(testDeck("d1"), onAppend = { Result.failure(PubkyError("Session rejected: 401 Unauthorized")) })
        val factory = FakeTenantFactory { pubky, secret -> FakeTenant(secret, sessionFor(pubky, secret), decks = decks) }
        val dispatcher = ToolDispatcher(PubkyEnvironment.Staging, TenantPool(factory, secrets, TenantLimits()), scratch)

        val error = dispatcher.call(alice, "card_add", args(BATCH)).envelope()["error"]!!.jsonObject

        assertEquals("session_expired", error["code"]!!.jsonPrimitive.content)
        assertEquals(true, error["reconnect"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("alice"), secrets.revoked)
    }

    @Test
    fun `an unreachable homeserver on a batch write is a network failure, not a revoke`() = runBlocking {
        val unreachable = PubkyError("Failed to import session: Request failed: HTTP transport error")
        val decks = FakeDeckRepository(testDeck("d1"), onAppend = { Result.failure(unreachable) })
        val factory = FakeTenantFactory { pubky, secret -> FakeTenant(secret, sessionFor(pubky, secret), decks = decks) }
        val dispatcher = ToolDispatcher(PubkyEnvironment.Staging, TenantPool(factory, secrets, TenantLimits()), scratch)

        val error = dispatcher.call(alice, "card_add", args(BATCH)).envelope()["error"]!!.jsonObject

        assertEquals("network", error["code"]!!.jsonPrimitive.content)
        assertEquals(false, error["reconnect"]!!.jsonPrimitive.boolean)
        assertEquals(emptyList(), secrets.revoked)
    }

    @Test
    fun `notes the CLI would have put on stderr travel in the envelope`() = runBlocking {
        val (dispatcher, _) = dispatcher(tenantWith(FakeDeckRepository(testDeck("d1"))))
        val envelope = dispatcher.call(
            alice,
            "card_add",
            args(
                """{"deck_id":"d1","front":"Dog","back":"Hund",""" +
                    """"front_image":"https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Dog.jpg/300px-Dog.jpg"}""",
            ),
        ).envelope()
        val notes = envelope["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(notes.any { "300px" in it }, notes.toString())
        assertNull(envelope["error"])
    }

    @Test
    fun `tag trending reads the tenant's index and names it`() = runBlocking {
        val (dispatcher, _) = dispatcher(tenantWith(FakeDeckRepository(testDeck())))
        val data = dispatcher.call(alice, "tag_trending", args("""{"limit":1}""")).envelope()["data"]!!.jsonObject
        assertEquals("spanish", data["tags"]!!.jsonArray.single().jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals(PubkyEnvironment.Staging.nexusBaseUrl, data["indexer"]!!.jsonPrimitive.content)
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.envelope(): JsonObject = assertNotNull(structuredContent)

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.errorCode(): String =
        envelope()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
}

private const val BATCH = """{"deck_id":"d1","cards":[{"front":"hola","back":"hello"},{"front":"adiós","back":"bye"}]}"""
