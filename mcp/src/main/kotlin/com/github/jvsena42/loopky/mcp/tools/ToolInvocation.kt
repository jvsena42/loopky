package com.github.jvsena42.loopky.mcp.tools

import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.Operand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path

/**
 * A tool call turned into the command line the CLI would have parsed.
 *
 * **argv, then `Args.parse`, then the same command function** — never a second reading of the
 * arguments. Everything the CLI validates (a blank id, a flag the command does not take, `--listen`
 * without a language pair) is validated by the same code here, because the MCP hands it the same
 * input. What this layer adds is only what a shell would have done: refuse what the schema does not
 * describe, and turn JSON values into tokens.
 */
class ToolInvocation(
    private val tool: McpTool,
    private val tokens: List<String>,
    private val operands: List<String>,
    /** The file the command reads, or null. Written by the server; its path is never the caller's. */
    val inline: InlineContent?,
    /** Every picture URL the call carries, for the [UrlGuard]. */
    val imageUrls: List<String>,
) {
    val command: String get() = tool.command.path

    /** The command line, with [file] standing where the command expects its file. */
    fun argv(file: Path?): List<String> = buildList {
        addAll(tool.command.path.split(' '))
        addAll(tokens)
        if (file != null && tool.inline == InlineInput.CardFile) add("--from-file=$file")
        // `--` ends the options, so an operand may start with a dash without being read as one.
        add("--")
        if (file != null && tool.inline == InlineInput.ImportSource) add(file.toString())
        addAll(operands)
    }

    companion object {
        /** Reads [arguments] against [tool]'s schema. A refusal is a [CliError] the caller reports. */
        fun of(tool: McpTool, arguments: JsonObject): ToolInvocation {
            tool.refuseUnknown(arguments)
            val operands = tool.operandTokens(arguments)
            val tokens = tool.optionTokens(arguments)
            val inline = InlineInputs.of(tool, arguments)
            val urls = tool.imageUrls(arguments) +
                (inline?.let { InlineInputs.urlsIn(String(it.bytes, Charsets.UTF_8)) }.orEmpty())
            return ToolInvocation(tool, tokens, operands, inline, urls.distinct())
        }

        /** A refused option is refused by its reason; anything else the schema lacks, by name. */
        private fun McpTool.refuseUnknown(arguments: JsonObject) {
            val accepted = acceptedProperties()
            val refusedByName = refused.mapKeys { propertyName(it.key) }
            arguments.keys.forEach { key ->
                refusedByName[key]?.let { reason -> throw CliError(ExitCode.Usage, "`$key` $reason") }
                if (key !in accepted) {
                    throw CliError(ExitCode.Usage, "`$name` has no input `$key`. It takes: ${accepted.joinToString(", ")}.")
                }
            }
        }

        private fun McpTool.acceptedProperties(): Set<String> = buildSet {
            operandNames().forEach { add(propertyName(it)) }
            exposed.forEach { add(propertyName(it)) }
            when (inline) {
                InlineInput.CardFile -> {
                    add(CARDS_PROPERTY)
                    add(TEXT_PROPERTY)
                }
                InlineInput.ImportSource -> add(TEXT_PROPERTY)
                null -> Unit
            }
        }

        /**
         * The operands in the table's order. Every required one has to be present *here*, because
         * they are positional: an optional `card_id` given without its `deck_id` would otherwise be
         * read as the deck.
         */
        private fun McpTool.operandTokens(arguments: JsonObject): List<String> {
            val required = (command.operand as? Operand.Opaque)?.required
                ?: (command.operand as? Operand.OneOf)?.let { listOf(it.name) }
                ?: emptyList()
            required.forEach { name ->
                if (propertyName(name) !in arguments) throw CliError(ExitCode.Usage, "Missing `${propertyName(name)}`.")
            }
            return operandNames().mapNotNull { name -> arguments[propertyName(name)]?.let { scalar(propertyName(name), it) } }
        }

        private fun McpTool.optionTokens(arguments: JsonObject): List<String> = buildList {
            exposedOptions.forEach { option ->
                val key = propertyName(option.name)
                val value = arguments[key] ?: return@forEach
                when {
                    option.isSwitch() -> if (flag(key, value)) add("--${option.name}")
                    option.name in REPEATABLE_OPTIONS -> repeated(key, value).forEach { add("--${option.name}=$it") }
                    else -> add("--${option.name}=${scalar(key, value)}")
                }
            }
        }

        private fun flag(key: String, value: JsonElement): Boolean = when ((value as? JsonPrimitive)?.contentOrNull) {
            "true" -> true
            "false" -> false
            else -> throw CliError(ExitCode.Usage, "`$key` is a boolean.")
        }

        private fun repeated(key: String, value: JsonElement): List<String> = when (value) {
            is JsonArray -> value.map { scalar(key, it) }
            is JsonPrimitive -> listOf(scalar(key, value))
            else -> throw CliError(ExitCode.Usage, "`$key` is an array of strings.")
        }

        /** A string, a number or a boolean, as the token the parser would have seen; never an object. */
        private fun scalar(key: String, value: JsonElement): String {
            val primitive = value as? JsonPrimitive ?: throw CliError(ExitCode.Usage, "`$key` is a string.")
            return primitive.contentOrNull ?: throw CliError(ExitCode.Usage, "`$key` cannot be null.")
        }

        private fun McpTool.imageUrls(arguments: JsonObject): List<String> =
            IMAGE_URL_OPTIONS.mapNotNull { option ->
                (arguments[propertyName(option)] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            }

        private val IMAGE_URL_OPTIONS = listOf("cover-url", "front-image", "back-image")
    }
}
