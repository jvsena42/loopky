package com.github.jvsena42.loopky.mcp.tools

import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.data.anki.ApkgReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** What a call sends in place of a file: the bytes, and the extension the reader picks a format by. */
class InlineContent(val bytes: ByteArray, val extension: String)

/** What inline content may be, per call. */
object InlineLimits {
    /** The most a `cards` array or `text` may hold, in UTF-8 bytes. */
    const val MAX_BYTES = 1024 * 1024

    /** The most cards one call may carry — array elements, or non-blank lines of `text`. */
    const val MAX_CARDS = 2_000

    /**
     * The most ids one `card_reorder` may carry. Not [MAX_CARDS]: a reorder names *every* card of
     * the deck, so that cap would refuse each deck past 2,000 cards — the decks where one
     * `card_move` per card costs most. [MAX_BYTES] still bounds the call.
     */
    const val MAX_CARD_IDS = 20_000
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
        if (tool.inline == InlineInput.CardOrder) {
            val ids = arguments[CARD_IDS_PROPERTY] ?: throw CliError(ExitCode.Usage, "Missing `$CARD_IDS_PROPERTY`.")
            return cardOrder(ids).checked()
        }
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

    /**
     * The picture addresses in [text], for the guard: the image columns of a delimited line or the
     * image keys of a JSONL one, never a URL inside prose. A networking deck has
     * `https://192.168.1.1/admin` on a back, and `loopky import` takes it; the guard is about what
     * a phone will be sent to fetch, and a back is not that.
     *
     * Deliberately wider than either reader rather than a copy of them: the raw line is split
     * (an empty front is the normal shape of a picture card, and trimming would shift its
     * columns), comment lines are checked too, a `{` line that is not JSON is read as columns,
     * and an import line is split on every separator it contains. Checking a column a reader
     * skips can only refuse a private address the reader would have ignored; missing one stores it.
     */
    fun imageUrlsIn(text: String, kind: InlineInput): List<String> =
        text.lineSequence().flatMap { imageFieldsOf(it, kind) }.filter { it.startsWith(HTTP, ignoreCase = true) }.distinct().toList()

    private fun imageFieldsOf(line: String, kind: InlineInput): List<String> {
        if (line.isBlank()) return emptyList()
        if (line.trimStart().startsWith('{')) {
            val row = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            if (row != null) {
                return IMAGE_KEYS.mapNotNull { key -> (row[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
            }
        }
        val separators = when (kind) {
            InlineInput.CardFile -> listOf(TAB)
            InlineInput.ImportSource -> IMPORT_SEPARATORS.filter { it in line }
            // Card ids, never a picture.
            InlineInput.CardOrder -> return emptyList()
        }
        return separators.flatMap { separator -> line.split(separator).drop(SIDES) }.map { it.trim() }.filter { it.isNotEmpty() }
    }

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

    /**
     * `card_ids` as the order file `card reorder` reads: one id a line. Each id is held to the shape
     * of one, because the file reader takes the first tab-separated column and skips `#` lines — an
     * id carrying either would be read as something other than what was sent.
     */
    @Suppress("ThrowsCount")
    private fun cardOrder(ids: JsonElement): InlineContent {
        val list = ids as? JsonArray ?: throw CliError(ExitCode.Usage, "`$CARD_IDS_PROPERTY` is an array of card ids.")
        if (list.isEmpty()) throw CliError(ExitCode.Usage, "`$CARD_IDS_PROPERTY` is empty; it names every card of the deck.")
        if (list.size > InlineLimits.MAX_CARD_IDS) {
            throw CliError(
                ExitCode.BadInput,
                "That is ${list.size} ids; one call takes at most ${InlineLimits.MAX_CARD_IDS}.",
            )
        }
        val lines = list.mapIndexed { index, element ->
            val id = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw CliError(ExitCode.BadInput, "`$CARD_IDS_PROPERTY`[$index] is not a string.")
            if (!id.isCardIdShaped()) {
                throw CliError(ExitCode.BadInput, "`$CARD_IDS_PROPERTY`[$index] is not a card id: \"$id\".")
            }
            id
        }
        return InlineContent(lines.joinToString("\n").toByteArray(Charsets.UTF_8), TXT)
    }

    private fun String.isCardIdShaped(): Boolean =
        isNotEmpty() && !startsWith('#') && none { it.isWhitespace() || it.isISOControl() }

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

    private val IMAGE_KEYS = listOf("front_image_url", "back_image_url")
    private const val TAB = "\t"
    private val IMPORT_SEPARATORS = listOf(TAB, ";", "|", " \u2014 ", " \u2013 ", " - ", ": ", ",")
    private const val HTTP = "http"

    /** The columns before the first image column: a front and a back. */
    private const val SIDES = 2
    private const val JSONL = "jsonl"
    private const val TXT = "txt"
    private const val HEADER_BYTES = 8
}
