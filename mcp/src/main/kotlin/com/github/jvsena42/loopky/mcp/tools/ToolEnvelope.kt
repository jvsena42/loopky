package com.github.jvsena42.loopky.mcp.tools

import com.github.jvsena42.loopky.cli.SCHEMA_VERSION
import com.github.jvsena42.loopky.cli.cliJson
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A tool result is the CLI's `--json` envelope, so a result means the same thing on both front
 * ends: `schema`, `ok`, `command`, `environment`, `indexer`, `update_available` (always null here —
 * a server is never stale from the caller's side) and `data`, with `error` on a failure. Two fields
 * are added under the same schema: `notes`, what the CLI would have put on stderr, and
 * `error.reconnect`, true when the link is dead and only the user can fix it.
 *
 * The CLI's own builders live in `:cli`, beside the update notice they carry; this is the same
 * shape without it, and `ToolEnvelopeTest` holds the field list.
 */
object ToolEnvelope {

    fun success(command: String, environment: PubkyEnvironment, data: JsonElement, notes: List<String>): JsonObject =
        buildJsonObject {
            common(command, environment, ok = true)
            put("data", data)
            notes(notes)
        }

    @Suppress("LongParameterList")
    fun failure(
        command: String,
        environment: PubkyEnvironment,
        code: String,
        exit: Int?,
        message: String,
        data: JsonElement? = null,
        notes: List<String> = emptyList(),
        reconnect: Boolean = false,
    ): JsonObject = buildJsonObject {
        common(command, environment, ok = false)
        put("data", data ?: JsonNull)
        notes(notes)
        putJsonObject("error") {
            put("code", code)
            put("exit", exit)
            put("message", message)
            put("reconnect", reconnect)
        }
    }

    /** The envelope as the tool's text content and, for clients that read it, its structured content. */
    fun JsonObject.asToolResult(): CallToolResult = CallToolResult(
        content = listOf(TextContent(cliJson.encodeToString(JsonElement.serializer(), this))),
        isError = this["ok"]?.toString() != "true",
        structuredContent = this,
    )

    private fun JsonObjectBuilder.common(
        command: String,
        environment: PubkyEnvironment,
        ok: Boolean,
    ) {
        put("schema", SCHEMA_VERSION)
        put("ok", ok)
        put("command", command)
        put("environment", environment.name.lowercase())
        put("indexer", environment.nexusBaseUrl)
        put("update_available", JsonNull)
    }

    private fun JsonObjectBuilder.notes(notes: List<String>) {
        putJsonArray("notes") { notes.forEach { add(JsonPrimitive(it)) } }
    }
}
