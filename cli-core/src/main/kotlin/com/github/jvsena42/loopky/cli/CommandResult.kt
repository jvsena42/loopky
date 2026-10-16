package com.github.jvsena42.loopky.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement

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
