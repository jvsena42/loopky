package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.pubky.PubkyError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `--json` envelope is an API surface an agent parses, so its shape is asserted rather than
 * assumed. A field may be added under `schema: 1`; a field's meaning may not change.
 */
class OutputTest {

    private fun parse(line: String) = Json.parseToJsonElement(line).jsonObject

    @Test
    fun `a success carries the schema, the command and the network it ran against`() {
        val json = parse(successEnvelope("deck list", "production", "https://nexus.pubky.app", JsonNull))
        assertEquals("1", json.getValue("schema").jsonPrimitive.content)
        assertTrue(json.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("deck list", json.getValue("command").jsonPrimitive.content)
        assertEquals("production", json.getValue("environment").jsonPrimitive.content)
        assertEquals("https://nexus.pubky.app", json.getValue("indexer").jsonPrimitive.content)
    }

    /**
     * The whole point of carrying the indexer on a *success*: Nexus answers a query aimed at the
     * wrong network successfully and empty, so an agent diffing intent against result has to be
     * able to see which network answered.
     */
    @Test
    fun `an empty result still says which network it came from`() {
        val json = parse(successEnvelope("tag trending", "staging", "https://nexus.staging.pubky.app", JsonNull))
        assertEquals("staging", json.getValue("environment").jsonPrimitive.content)
        assertEquals("https://nexus.staging.pubky.app", json.getValue("indexer").jsonPrimitive.content)
    }

    /**
     * `update_available` is null in the ordinary case, and a caller that ignores the field stays
     * correct — which is what makes adding it legal under `schema: 1`.
     */
    @Test
    fun `every envelope carries update_available, null when there is nothing to say`() {
        val success = parse(successEnvelope("deck list", "production", "x", JsonNull))
        assertEquals(JsonNull, success.getValue("update_available"))
        val failure = parse(failureEnvelope("deck list", "production", "x", CliError(ExitCode.Network, "down")))
        assertEquals(JsonNull, failure.getValue("update_available"))
    }

    /**
     * When there *is* something to say it is an object, not a bare `true`: a newer CLI at a
     * different envelope schema means the reader's own parser may be wrong, which is a different
     * severity from a version bump and gets its own field rather than being folded into one bit.
     */
    @Test
    fun `an available update reports its version and whether the schema moved`() {
        val update = UpdateAvailable(version = "0.9.0", schema = 2, schemaChanged = true)
        val json = parse(successEnvelope("deck list", "production", "x", JsonNull, update))
            .getValue("update_available").jsonObject
        assertEquals("0.9.0", json.getValue("version").jsonPrimitive.content)
        assertEquals("2", json.getValue("schema").jsonPrimitive.content)
        assertTrue(json.getValue("schema_changed").jsonPrimitive.content.toBoolean())

        // And on a failure too: an agent whose command just failed is exactly the one that wants
        // to know its client is stale.
        val failed = parse(failureEnvelope("deck list", "production", "x", CliError(ExitCode.Network, "down"), update))
        assertEquals("0.9.0", failed.getValue("update_available").jsonObject.getValue("version").jsonPrimitive.content)
    }

    @Test
    fun `a failure repeats the exit status inside the payload`() {
        val json = parse(
            failureEnvelope("deck show", "production", "x", CliError(ExitCode.SessionExpired, "gone")),
        )
        assertFalse(json.getValue("ok").jsonPrimitive.content.toBoolean())
        val error = json.getValue("error").jsonObject
        assertEquals("session_expired", error.getValue("code").jsonPrimitive.content)
        assertEquals("4", error.getValue("exit").jsonPrimitive.content)
        assertEquals("gone", error.getValue("message").jsonPrimitive.content)
    }

    @Test
    fun `every exit code has a distinct number and a distinct name`() {
        assertEquals(ExitCode.entries.size, ExitCode.entries.map { it.code }.toSet().size)
        assertEquals(ExitCode.entries.size, ExitCode.entries.map { it.json }.toSet().size)
    }

    /**
     * Session expiry has to be distinguishable from a network wobble. It is hourly and
     * unrecoverable without a human (#165); an agent told the wrong one either retries a dead
     * session forever or abandons a working network.
     */
    @Test
    fun `session expiry maps to its own code, apart from offline`() {
        // The wording the FFI actually produces. `isSessionExpired` looks for "session" beside
        // import/expired/invalid rather than for a status, because the fork wraps every
        // `*_with_session` failure behind one prefix.
        val expired = PubkyError("Failed to import session: session is invalid or expired")
        val offline = PubkyError("HTTP transport error: error sending request")
        assertEquals(ExitCode.SessionExpired, ExitCode.of(expired))
        assertEquals(ExitCode.Network, ExitCode.of(offline))
    }

    /** 507 is terminal — nothing the client can do digs out of a full quota, so it never retries. */
    @Test
    fun `a full homeserver quota maps to its own terminal code`() {
        val full = PubkyError("Request failed: Server responded with an error: 507 Insufficient Storage")
        assertEquals(ExitCode.StorageFull, ExitCode.of(full))
    }

    @Test
    fun `a missing record maps to not found`() {
        val missing = PubkyError("Request failed: Server responded with an error: 404 Not Found")
        assertEquals(ExitCode.NotFound, ExitCode.of(missing))
    }
}
