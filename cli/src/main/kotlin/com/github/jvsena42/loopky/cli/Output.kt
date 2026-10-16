package com.github.jvsena42.loopky.cli

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
