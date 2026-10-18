package com.github.jvsena42.loopky.mcp.tools

import com.github.jvsena42.loopky.cli.CliOption
import com.github.jvsena42.loopky.cli.Operand
import com.github.jvsena42.loopky.cli.OptionValue
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A tool's MCP description, derived from the command table and nothing else.
 *
 * Operands become string properties (required ones required), options become typed properties by
 * their [OptionValue] — a switch is a boolean, a closed set an enum, a repeatable option an array —
 * and the inline input adds `cards` or `text`. The summaries are the table's, so a flag renamed
 * there is renamed here with no second edit.
 */
fun McpTool.toTool(): Tool = Tool(
    name = name,
    title = title,
    description = description(),
    inputSchema = ToolSchema(properties = properties(), required = required()),
    annotations = ToolAnnotations(
        title = title,
        readOnlyHint = !command.writes,
        destructiveHint = hints.destructive,
        idempotentHint = hints.idempotent,
        // `tag trending` reads a public index; everything else stays inside the user's account.
        openWorldHint = !command.needsSession,
    ),
)

const val CARDS_PROPERTY = "cards"
const val TEXT_PROPERTY = "text"

/** The keys a `cards` object may carry, which are the card-file row's. */
val CARD_KEYS = setOf("id", "front", "back", "front_image_url", "back_image_url")

private fun McpTool.description(): String = buildString {
    append((summary ?: command.summary).replaceFirstChar { it.uppercase() })
    append('.')
    if (command.writes) append(" Writes to the user's homeserver.")
    when (inline) {
        InlineInput.CardFile -> append(
            " Several cards at once go in `cards` (objects) or `text` (TSV `front<TAB>back[<TAB>front_image_url" +
                "<TAB>back_image_url]`, or JSONL); one card goes in `front`/`back`.",
        )
        InlineInput.ImportSource -> append(
            " The deck's text goes in `text`: one card per line, front and back split by the `separator` " +
                "(auto-detected by default). An Anki .apkg cannot be imported here.",
        )
        null -> Unit
    }
    if ("dry-run" in exposed) append(" With `dry_run` nothing is written and the result reports what would be.")
}

private fun McpTool.properties(): JsonObject = buildJsonObject {
    operandProperties(this@properties)
    exposedOptions.forEach { option -> putJsonObject(propertyName(option.name)) { optionSchema(option) } }
    when (inline) {
        InlineInput.CardFile -> {
            putJsonObject(CARDS_PROPERTY) {
                put("type", "array")
                put(
                    "description",
                    "Cards to write, each {front, back, front_image_url?, back_image_url?} — and `id` for an edit.",
                )
                putJsonObject("items") { cardObjectSchema() }
                put("maxItems", InlineLimits.MAX_CARDS)
            }
            putJsonObject(TEXT_PROPERTY) {
                put("type", "string")
                put("description", "The same cards as a TSV or JSONL document instead of `cards`.")
            }
        }
        InlineInput.ImportSource -> putJsonObject(TEXT_PROPERTY) {
            put("type", "string")
            put("description", "The deck as text: one card per line, two columns.")
        }
        null -> Unit
    }
}

private fun JsonObjectBuilder.operandProperties(tool: McpTool) {
    val operand = tool.command.operand
    tool.operandNames().forEach { name ->
        putJsonObject(propertyName(name)) {
            put("type", "string")
            if (operand is Operand.OneOf) {
                putJsonArray("enum") { operand.choices.forEach { add(it) } }
            } else {
                put("description", "The $name, as `deck_list` or `card_list` report it.")
            }
        }
    }
}

private fun McpTool.required(): List<String> = buildList {
    (command.operand as? Operand.Opaque)?.required?.forEach { add(propertyName(it)) }
    (command.operand as? Operand.OneOf)?.let { add(propertyName(it.name)) }
    if (inline == InlineInput.ImportSource) add(TEXT_PROPERTY)
}

private fun JsonObjectBuilder.optionSchema(option: CliOption) {
    put("description", option.summary.withInputNames())
    when (val value = option.value) {
        OptionValue.Switch -> put("type", "boolean")
        is OptionValue.OneOf -> {
            put("type", "string")
            putJsonArray("enum") { value.choices.forEach { add(it) } }
        }
        OptionValue.Text -> textSchema(option.name)
        // Never reached: a path option is refused by every tool, and `ToolCatalogTest` says so.
        OptionValue.Path -> put("type", "string")
    }
}

/** `--id` as the input a tool takes, `id`: a CLI flag name means nothing to a tool caller. */
private fun String.withInputNames(): String = replace(CLI_FLAG) { "`${propertyName(it.groupValues[1])}`" }

private val CLI_FLAG = Regex("--([a-z][a-z-]*)")

private fun JsonObjectBuilder.textSchema(name: String) {
    when (name) {
        in REPEATABLE_OPTIONS -> {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
        }
        in INTEGER_OPTIONS -> {
            put("type", "integer")
            put("minimum", 1)
        }
        else -> put("type", "string")
    }
}

private fun JsonObjectBuilder.cardObjectSchema() {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("id") { put("type", "string") }
        // Null is "clear this field" on an edit, the way a JSONL card file spells it.
        (CARD_KEYS - "id").forEach { key ->
            putJsonObject(key) {
                putJsonArray("type") {
                    add("string")
                    add("null")
                }
            }
        }
    }
    put("additionalProperties", false)
}
