package com.github.jvsena42.loopky.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * The `--json` envelope, version 1.
 *
 * Versioned from the first release because agents parse it, which makes it an API surface with
 * compatibility obligations rather than a print format (#54, open question 1). Fields may be
 * added; a field's meaning may not change under the same [SCHEMA_VERSION].
 *
 * It is also the **verification** channel, not just the output format. An agent cannot look at a
 * screenshot to check that the picture it attached is the right picture, so reads have to echo
 * back what was actually stored — image refs and tags included, not only text — and a caller
 * diffs intent against result from these bytes.
 *
 * Two fields exist purely so a wrong answer cannot look like an empty one: `environment` and the
 * indexer URL travel on every result, because a Nexus query aimed at the wrong network answers
 * *successfully* with `[]`.
 */
const val SCHEMA_VERSION = 1

/** What a command produced: the machine shape, and the same thing for a person. */
class CommandResult(val data: JsonElement, val text: String)

/** JSON for the `--json` channel. Pretty-printed off by design — one result, one line, greppable. */
val cliJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = false
}

/** Builds a [CommandResult] from a `@Serializable` payload and the human rendering of it. */
inline fun <reified T> result(payload: T, text: String): CommandResult =
    CommandResult(cliJson.encodeToJsonElement(payload), text)

/** A command with nothing to report but success. */
fun ok(text: String): CommandResult = CommandResult(JsonNull, text)

/**
 * The success envelope.
 *
 * [environment] and [indexer] are on *every* result on purpose — see the note on
 * [SCHEMA_VERSION]. They are what lets a caller notice it read the wrong network rather than an
 * empty one.
 */
fun successEnvelope(
    command: String,
    environment: String,
    indexer: String,
    data: JsonElement,
    update: UpdateAvailable? = null,
): String = cliJson.encodeToString(
    JsonElement.serializer(),
    buildJsonObject {
        put("schema", SCHEMA_VERSION)
        put("ok", true)
        put("command", command)
        put("environment", environment)
        put("indexer", indexer)
        put("update_available", updateJson(update))
        put("data", data)
    },
)

/**
 * The failure envelope.
 *
 * `exit` repeats the process's exit status inside the payload so a caller that only reads stdout —
 * a pipe, a log, an MCP wrapper handed the string — has the same information as a shell.
 *
 * `data` is the success shape of whatever the command *had* done when it failed, or null. It is on
 * the failure envelope for the same reason `error` is: a partly-applied batch is the one outcome
 * where the exit code alone leaves a caller unable to act, since "nothing happened" and "35 of your
 * 665 rows are now on the homeserver" are the same exit 12 without it.
 */
fun failureEnvelope(
    command: String,
    environment: String,
    indexer: String,
    error: CliError,
    update: UpdateAvailable? = null,
): String = cliJson.encodeToString(
    JsonElement.serializer(),
    buildJsonObject {
        put("schema", SCHEMA_VERSION)
        put("ok", false)
        put("command", command)
        put("environment", environment)
        put("indexer", indexer)
        put("update_available", updateJson(update))
        put("data", error.data ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", error.exitCode.json)
                put("exit", error.exitCode.code)
                put("message", error.message.orEmpty())
            },
        )
    },
)

/**
 * `update_available`, the envelope's newest field (#209).
 *
 * A **nullable object**, not a boolean, and both halves of that are deliberate. Null covers every
 * "nothing to say" — up to date, the check is off, the check failed, no release page yet — so a
 * caller branches on truthiness in any language without a special case. And when there *is*
 * something to say it is not one bit: `schema_changed` is a different severity from a version
 * bump, because a newer CLI at a different envelope schema means the reader's own parser may be
 * wrong, which is what versioning this envelope from the first release was for.
 *
 * Adding a field is allowed under `schema: 1`; changing one's meaning is not. It landed before the
 * schema had consumers rather than after, which was the point of doing it early.
 */
private fun updateJson(update: UpdateAvailable?): JsonElement =
    if (update == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("version", update.version)
            put("schema", update.schema)
            put("schema_changed", update.schemaChanged)
        }
    }

/**
 * An out-of-band event, for the one command that has something to say before it finishes.
 *
 * `login` prints the auth URL and then blocks until Pubky Ring approves, so its output is two
 * lines rather than one: this event, then the ordinary envelope. A parser that ignores any line
 * whose `event` it does not know stays correct.
 *
 * [ok] defaults to true because the first event could not fail — an auth URL is either printed or
 * the command is over. `batch` streams one of these per operation and **must** pass its own
 * outcome: hardcoding true here meant a run whose writes were failing streamed a line saying `ok`
 * for every one of them, and branching on the envelope's `ok` is the obvious way to read a stream
 * of envelopes.
 */
fun eventEnvelope(command: String, event: String, data: JsonElement, ok: Boolean = true): String =
    cliJson.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schema", SCHEMA_VERSION)
            put("ok", ok)
            put("command", command)
            put("event", event)
            put("data", data)
        },
    )
