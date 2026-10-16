package com.github.jvsena42.loopky.mcp.tools

import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.data.anki.ApkgReader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a call sends in place of a file: the bytes, and the extension the reader picks a format by. */
class InlineContent(val bytes: ByteArray, val extension: String)

/** What inline content may be, per call. */
object InlineLimits {
    /** The most a `cards` array or `text` may hold, in UTF-8 bytes. */
    const val MAX_BYTES = 1024 * 1024

    /** The most cards one call may carry — array elements, or non-blank lines of `text`. */
    const val MAX_CARDS = 2_000
}

/**
 * The `cards` and `text` inputs, read into the bytes a card file or an import would hold.
 *
 * `cards` becomes JSONL in the card file's own flat shape, so the same reader validates it the
 * same way; `text` is taken as given. Both are capped, and both are refused when they are an
 * archive however they were named: the `.apkg` reader would open that as a SQLite collection,
 * which is exactly the parsing a hosted server does not do on a caller's bytes (#486).
 */
object InlineInputs {

    @Suppress("ThrowsCount")
    fun of(tool: McpTool, arguments: JsonObject): InlineContent? {
        val cards = arguments[CARDS_PROPERTY]
        val text = arguments[TEXT_PROPERTY]
        if (tool.inline == null || (cards == null && text == null)) {
            if (tool.inline == InlineInput.ImportSource) throw CliError(ExitCode.Usage, "Missing `$TEXT_PROPERTY`.")
            return null
        }
        if (cards != null && text != null) {
            throw CliError(ExitCode.Usage, "Give `$CARDS_PROPERTY` or `$TEXT_PROPERTY`, not both.")
        }
        val content = if (cards != null) cardsAsJsonl(cards) else textContent(requireNotNull(text))
        return content.checked()
    }

    /** Every address in [text], for the guard: a TSV's picture columns or a JSONL's URL fields. */
    fun urlsIn(text: String): List<String> = URL_IN_TEXT.findAll(text).map { it.value }.toList()

    private fun cardsAsJsonl(cards: JsonElement): InlineContent {
        val rows = cards as? JsonArray ?: throw CliError(ExitCode.Usage, "`$CARDS_PROPERTY` is an array of objects.")
        if (rows.size > InlineLimits.MAX_CARDS) throw tooMany(rows.size)
        val lines = rows.mapIndexed { index, row -> cardLine(index, row) }
        return InlineContent(lines.joinToString("\n").toByteArray(Charsets.UTF_8), JSONL)
    }

    /** One `cards` object as the JSONL line a card file would hold: flat, strings and nulls only. */
    @Suppress("ThrowsCount")
    private fun cardLine(index: Int, row: JsonElement): String {
        val card = row as? JsonObject ?: throw CliError(ExitCode.BadInput, "`$CARDS_PROPERTY`[$index] is not an object.")
        val stray = card.keys - CARD_KEYS
        if (stray.isNotEmpty()) {
            throw CliError(
                ExitCode.BadInput,
                "`$CARDS_PROPERTY`[$index] has keys a card does not: ${stray.joinToString()}. " +
                    "A card is {id?, front, back, front_image_url?, back_image_url?}.",
            )
        }
        if (card.values.any { it !is JsonPrimitive && it !is JsonNull }) {
            throw CliError(ExitCode.BadInput, "`$CARDS_PROPERTY`[$index] has a nested value; use strings.")
        }
        return card.toString()
    }

    private fun textContent(text: JsonElement): InlineContent {
        val body = (text as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw CliError(ExitCode.Usage, "`$TEXT_PROPERTY` is a string.")
        val lines = body.lineSequence().count { it.isNotBlank() }
        if (lines > InlineLimits.MAX_CARDS) throw tooMany(lines)
        return InlineContent(body.toByteArray(Charsets.UTF_8), TXT)
    }

    private fun InlineContent.checked(): InlineContent {
        if (bytes.size > InlineLimits.MAX_BYTES) {
            throw CliError(
                ExitCode.BadInput,
                "The content is ${bytes.size} bytes; one call takes at most ${InlineLimits.MAX_BYTES}. " +
                    "Split it over several calls.",
            )
        }
        if (ApkgReader.canRead(bytes.copyOf(HEADER_BYTES))) {
            throw CliError(ExitCode.BadInput, "This is an archive. An Anki .apkg cannot be imported here; send text.")
        }
        return this
    }

    private fun tooMany(count: Int) = CliError(
        ExitCode.BadInput,
        "That is $count cards; one call takes at most ${InlineLimits.MAX_CARDS}. Split them over several calls.",
    )

    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>\\]+""")
    private const val JSONL = "jsonl"
    private const val TXT = "txt"
    private const val HEADER_BYTES = 8
}
