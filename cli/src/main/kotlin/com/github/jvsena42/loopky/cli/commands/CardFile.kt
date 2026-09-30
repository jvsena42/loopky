package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.ordForIndex
import com.github.jvsena42.loopky.util.generateId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * A batch of cards, from a file — the shape both `card add --from-file` and `card edit --from-file`
 * take.
 *
 * **Batch mutation is a v1 requirement, not a convenience.** One card write is one chunk
 * read-modify-write plus a manifest patch plus a `/session` round trip (#105); 190 image attachments
 * done one at a time cost ~30–40 s each. A surface offering only `card edit <deckId> <cardId>` gets
 * used in a loop, and editing is precisely what an agent does *after* an import.
 *
 * Two formats, and the choice is not stylistic:
 *
 * - **TSV**, `front <TAB> back <TAB> front_image_url <TAB> back_image_url`, the last two optional.
 *   Image columns are here from day one because since #167 a card image can be a **remote ref** — a
 *   URL, no bytes on the wire, no quota spent — which is the gap neither `.apkg` nor
 *   TSV-through-the-paste-parser can express: both carry a picture only when a field is *nothing but*
 *   that image, so "this side has text and a picture" had no representation.
 * - **JSONL**, one object per line, for what TSV cannot hold: a side containing a tab or newline, and
 *   — for `card edit` — naming which card to change and which fields to leave alone.
 *
 * The format is chosen by extension, then content, never by a flag: a caller that has to remember
 * `--jsonl` will one day not, and a JSON object read as TSV is a card whose front is `{"id":`.
 */
@Serializable
data class CardFileRow(
    /** Only for `card edit`; ignored on add, where every row is a new card. */
    val id: String? = null,
    val front: String? = null,
    val back: String? = null,
    @SerialName("front_image_url") val frontImageUrl: String? = null,
    @SerialName("back_image_url") val backImageUrl: String? = null,
) {
    val isEmpty: Boolean
        get() = front.isNullOrBlank() && back.isNullOrBlank() &&
            frontImageUrl.isNullOrBlank() && backImageUrl.isNullOrBlank()

    /**
     * A row that could become a card: something on **both** sides, text or a picture. False is not
     * the same as [isEmpty] — an edit row may carry one side and mean "leave the other alone", so
     * this is asked only where a row becomes a *new* card.
     */
    val hasBothSides: Boolean
        get() = !(front.isNullOrBlank() && frontImageUrl.isNullOrBlank()) &&
            !(back.isNullOrBlank() && backImageUrl.isNullOrBlank())

    fun toCard(deckId: String, now: Long, index: Int): Card = Card(
        id = generateId(),
        deckId = deckId,
        updatedAt = now,
        front = CardSide(
            text = front?.takeIf { it.isNotBlank() },
            imageRef = frontImageUrl?.takeIf { it.isNotBlank() }?.let(::remoteImage),
        ),
        back = CardSide(
            text = back?.takeIf { it.isNotBlank() },
            imageRef = backImageUrl?.takeIf { it.isNotBlank() }?.let(::remoteImage),
        ),
        ord = ordForIndex(index),
    )
}

private val cardFileJson = Json { ignoreUnknownKeys = true }

/**
 * Read [path] — or stdin, when it is `-` — as a batch of cards.
 *
 * Blank lines are skipped and `# ` comments honoured in TSV, so a generated file can carry a header
 * without a phantom first card — `#` **followed by whitespace**, so a card whose front is `#1 ranked`
 * survives.
 *
 * A row with nothing on either side is an error rather than a silent skip, and so is an image column
 * holding something that is not a URL: a file producing fewer cards than it has lines is exactly the
 * kind of loss `--json` exists to make visible.
 */
internal fun readCardFile(
    path: String,
    log: ImageAdviceLog = ImageAdviceLog(),
    onNote: (String) -> Unit = System.err::println,
): List<CardFileRow> {
    val text = if (path == "-") {
        System.`in`.readBytes().toString(Charsets.UTF_8)
    } else {
        val file = File(path)
        if (!file.isFile) throw CliError(ExitCode.BadInput, "No such file: $path")
        file.readText(Charsets.UTF_8)
    }
    val rows = if (looksLikeJsonl(path, text)) parseJsonl(text, log, onNote) else parseTsv(text, log)
    if (rows.isEmpty()) throw CliError(ExitCode.BadInput, "$path held no cards.")
    return rows
}

private fun looksLikeJsonl(path: String, text: String): Boolean =
    path.endsWith(".jsonl", ignoreCase = true) ||
        path.endsWith(".ndjson", ignoreCase = true) ||
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart()?.startsWith("{") == true

private fun parseJsonl(text: String, log: ImageAdviceLog, onNote: (String) -> Unit): List<CardFileRow> {
    var blobImages = 0
    val rows = text.lineSequence()
        .withIndex()
        .filter { (_, line) -> line.isNotBlank() }
        .map { (index, line) ->
            val where = "Line ${index + 1}"
            val json = runCatching { cardFileJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
                ?: throw CliError(ExitCode.BadInput, "$where is not a card object.")
            val flat = json.flattened { blobImages++ }
            runCatching { cardFileJson.decodeFromJsonElement(CardFileRow.serializer(), flat) }.getOrElse {
                throw CliError(ExitCode.BadInput, "$where is not a card object: ${it.message}")
            }
        }
        .onEachIndexed { index, row ->
            if (row.isEmpty) throw CliError(ExitCode.BadInput, "A row has neither text nor an image.")
            // Checked here and not only in the TSV columns: a JSONL row names its image fields
            // outright, so there is no "is this a picture or prose" question to answer — but an
            // unrenderable URL still has to be refused before `toCard` turns it into a ref, or it
            // surfaces as exit 1 "internal" plus a Kotlin assertion for a typo in someone's file.
            // Blank is the documented way to clear a picture, here as at `--back-image=`, so it
            // skips the check rather than being refused as an address that could never render.
            row.frontImageUrl?.takeIf { it.isNotBlank() }
                ?.let { log.checked(it, "Line ${index + 1}, front_image_url") }
            row.backImageUrl?.takeIf { it.isNotBlank() }
                ?.let { log.checked(it, "Line ${index + 1}, back_image_url") }
        }
        .toList()
    if (blobImages > 0) {
        onNote(
            "loopky: $blobImages side(s) carry a homeserver blob image rather than a URL, which " +
                "this format cannot express — those pictures were left as they are. Only a URL " +
                "image can be set from a card file.",
        )
    }
    return rows
}

/**
 * A row in the flat shape, whichever of the two it arrived in.
 *
 * `card list --json` emits `{"front":{"text":…,"image":{"url":…}},…}` while a card file takes
 * `{"front":"…","front_image_url":"…"}`, so answering "has this row already been applied?" needed
 * a shape check *and* a decode of the URL — and getting it wrong rewrites every row on every pass
 * (#229, item 3). Both shapes are accepted here, per side, so `card list --json | jq` output can
 * be edited and fed straight back.
 *
 * The tri-state survives the translation, which is the part worth getting right: a **key that is
 * absent** leaves that field alone, and a key present and null clears it. `card list --json`
 * writes explicit nulls, so feeding its output back sets every field to exactly what it read —
 * which is what makes the round trip idempotent rather than merely accepted.
 */
private fun JsonObject.flattened(onBlobImage: () -> Unit): JsonObject {
    val fields = mutableMapOf<String, JsonElement>()
    // The flat keys first, so a nested side overrides them rather than racing them.
    FLAT_KEYS.forEach { key -> this[key]?.takeIf { it !is JsonObject }?.let { fields[key] = it } }
    SIDES.forEach { (side, imageKey) ->
        val nested = this[side] as? JsonObject ?: return@forEach
        nested.textOverride()?.let { fields[side] = it }
        nested.imageOverride(onBlobImage)?.let { fields[imageKey] = it }
    }
    return JsonObject(fields)
}

/** A side object's `text`, as the flat shape spells it: absent stays absent, null becomes "clear". */
private fun JsonObject.textOverride(): JsonElement? = when (val text = this["text"]) {
    null -> null
    JsonNull -> JsonPrimitive("")
    else -> text
}

/**
 * A side object's `image`, as a URL.
 *
 * A blob ref — `sha256` set, no `url` — is left **unchanged** rather than cleared, and counted so
 * the caller can be told once. A card file has no way to name a blob, so treating "I cannot
 * express this" as "remove it" would strip the pictures off every `.apkg`-imported card the moment
 * someone round-tripped a deck through `card list`.
 */
private fun JsonObject.imageOverride(onBlobImage: () -> Unit): JsonElement? {
    val image = this["image"] ?: return null
    if (image is JsonNull) return JsonPrimitive("")
    if (image !is JsonObject) return image
    val url = (image["url"] as? JsonPrimitive)?.contentOrNull
    if (url == null) onBlobImage()
    return url?.let(::JsonPrimitive)
}

/** The flat card-file keys, copied through untouched. */
private val FLAT_KEYS = listOf("id", "front", "back", "front_image_url", "back_image_url")

/** Each side and the flat key its picture lands under. */
private val SIDES = listOf("front" to "front_image_url", "back" to "back_image_url")

private fun parseTsv(text: String, log: ImageAdviceLog): List<CardFileRow> =
    text.lineSequence()
        .withIndex()
        .filter { (_, line) -> line.isNotBlank() && !line.isComment() }
        .map { (index, line) ->
            // `split`, not a CSV reader: the separator is a tab, which is the one character a card
            // side reliably does not contain, and quoting rules would only add a way to get it
            // wrong. A side that needs a tab or a newline uses JSONL.
            val fields = line.split('\t')
            CardFileRow(
                front = fields.getOrNull(FRONT_COLUMN)?.trim(),
                back = fields.getOrNull(BACK_COLUMN)?.trim(),
                frontImageUrl = fields.imageUrlAt(FRONT_IMAGE_COLUMN, index, log),
                backImageUrl = fields.imageUrlAt(BACK_IMAGE_COLUMN, index, log),
            ).also {
                if (it.isEmpty) {
                    throw CliError(ExitCode.BadInput, "Line ${index + 1} has neither text nor an image.")
                }
            }
        }
        .toList()

/** The four TSV columns, in the order the format documents them. */
private const val FRONT_COLUMN = 0
private const val BACK_COLUMN = 1
private const val FRONT_IMAGE_COLUMN = 2
private const val BACK_IMAGE_COLUMN = 3

/**
 * Refuse a batch holding a card with nothing on one of its sides.
 *
 * Checked here rather than left to `publish`, which `require`s the same thing but throws an
 * `IllegalArgumentException` no classifier recognises — so it would reach the user as exit 1
 * "internal" plus a Kotlin assertion message, for a blank column in their own file. The message names
 * the row, since the point of a batch is that nobody reads it line by line.
 */
internal fun List<CardFileRow>.requireBothSides(): List<CardFileRow> = onEachIndexed { index, row ->
    if (!row.hasBothSides) {
        throw CliError(
            ExitCode.BadInput,
            "Row ${index + 1} has nothing on one side; a card needs a front and a back. " +
                "An image counts as a side.",
        )
    }
}

/**
 * A comment line: `#` followed by whitespace, never a bare `#`. The space is load-bearing —
 * `startsWith("#")` alone silently swallows a card whose front is `#1 ranked`, a markdown heading or
 * a hashtag.
 */
private fun String.isComment(): Boolean =
    startsWith("#") && (length == 1 || this[1].isWhitespace())

/**
 * The image column at [column], refused if it is not a URL.
 *
 * Every app-side constructor of a remote `MediaRef.Image` gets its URL from a picker; this is the
 * first path where an arbitrary string reaches one. Without the check, a 3-column Anki export
 * (Front / Back / Example sentence) publishes every card with
 * `MediaRef.Image(url = "una manzana roja")`, both apps try to load prose as an image, and `--json`
 * reports success.
 *
 * An error rather than a fall-through, unlike the import path: the four-column TSV is what this file
 * *documents*.
 */
private fun List<String>.imageUrlAt(column: Int, lineIndex: Int, log: ImageAdviceLog): String? {
    val value = getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!value.looksLikeImageUrl()) {
        throw CliError(
            ExitCode.BadInput,
            "Line ${lineIndex + 1}, column ${column + 1} is an image column but holds " +
                "\"${value.take(IMAGE_URL_ERROR_EXCERPT)}\" — it must be an https:// URL. " +
                "A two-column file has no image columns at all.",
        )
    }
    return log.checked(value, "Line ${lineIndex + 1}, column ${column + 1}")
}

/**
 * Whether a value is *shaped* like an image column — a scheme and nothing else.
 *
 * Deliberately looser than [isRenderableImageUrl], and not interchangeable with it. This one answers
 * "is this column pictures or prose", which decides how a whole file is read; the strict one answers
 * "could this ever render", which decides whether a single card is written. An `http://` address is a
 * clear answer to the first question, and refusing it here would send the whole import through the
 * text parser, silently turning every picture into a third card side.
 */
internal fun String.looksLikeImageUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private const val IMAGE_URL_ERROR_EXCERPT = 40
