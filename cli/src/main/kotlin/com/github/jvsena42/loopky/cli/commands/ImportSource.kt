package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.data.anki.BulkNote
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.Separator
import java.io.File

/**
 * Turning the operand of `loopky import` into a parsed draft.
 *
 * Split out of `Import.kt` so that file is the *command* — resume, publish, the envelope — and this
 * one is everything about reading what the user pointed at. Three sources land here and only one of
 * them is new; the shape of the thing is that they converge:
 *
 * - ordinary text, through the app's own `parseBulk` (spec §6 separators, §9 edge cases);
 * - a tab-separated file with **image columns**, a format this client defines, through
 *   `parseBulkNotes`;
 * - an Anki **`.apkg`**, through the shared reader, also through `parseBulkNotes`.
 *
 * The convergence is the rule rather than a coincidence: #54 requires every import source to reuse
 * one spine, and `parseBulkNotes` is that spine's entry point for a source that knows its own
 * structure. Dedupe, the caps, truncation reporting and the drop-incomplete policy are shared by
 * all three; only the splitting differs, and for two of them there is none.
 */

internal class ParsedSource(
    val draft: ImportDraft,
    val droppedColumns: Boolean,
    val format: ImportFormat,
    val apkg: ApkgSummary?,
    /**
     * How the file was split, as the envelope and the human summary both name it.
     *
     * Carried here rather than read off the draft because the draft cannot answer it. Both
     * structured entry points stamp `Separator.Tab` on a draft and set `structured`, so the field
     * says the same thing for an `.apkg` — which really is never split — and for a four-column
     * TSV, which is split on tabs by this client's own reader. Reporting `"none"` for the second
     * read as a parse failure of a file that had parsed perfectly (#257, item 7).
     */
    val separator: String,
)

internal suspend fun parseSource(
    args: Args,
    imports: ImportRepository,
    source: String,
    title: String?,
    keepImageBytes: Boolean,
): ParsedSource {
    val parsed = when (sourceFormat(source)) {
        ImportFormat.Apkg -> parseApkg(args, imports, source, title, keepImageBytes)
        ImportFormat.Text -> parseText(args, imports, source, title)
    }

    if (imports.keptRows().isEmpty()) {
        imports.clear()
        throw CliError(ExitCode.BadInput, "Nothing importable in $source.")
    }
    return parsed
}

/**
 * Which reader [source] goes to, and the one place stdin is ruled out for one of them.
 *
 * A SQLite driver opens a **path**, not a stream, so an `.apkg` cannot arrive on stdin at all.
 * Said out loud rather than left to fail as "nothing importable": a piped archive otherwise reads
 * as text and produces a deck of cards whose fronts are fragments of a zip header.
 */
private fun sourceFormat(source: String): ImportFormat {
    if (source == "-") return ImportFormat.Text
    return detectImportFormat(source) { fileHeader(source) }
}

private suspend fun parseApkg(
    args: Args,
    imports: ImportRepository,
    source: String,
    title: String?,
    keepImageBytes: Boolean,
): ParsedSource {
    if (args.option("separator") != null) {
        throw CliError(
            ExitCode.Usage,
            "--separator means nothing for an .apkg: an Anki collection stores typed fields, and " +
                "nothing about it is split. Use --$FRONT_FIELD_OPTION / --$BACK_FIELD_OPTION to " +
                "choose which two fields become the card.",
        )
    }
    if (!File(source).isFile) throw CliError(ExitCode.BadInput, "No such file: $source")

    val read = readApkg(source, args, keepBytes = keepImageBytes)
    val draft = imports.parseBulkNotes(
        notes = read.import.notes,
        suggestedTitle = title,
        // Neither the deck's own description nor its note tags are adopted — only reported. See
        // ApkgSummary: the description is AnkiWeb boilerplate more often than not, and a tag is a
        // public record this client will not write on a deck nobody asked it to.
        suggestsReverse = read.import.reversible,
    ).getOrElse { throw asCliError(it) }
    return ParsedSource(
        draft = draft,
        droppedColumns = false,
        format = ImportFormat.Apkg,
        apkg = read.toSummary(),
        // An Anki collection stores typed fields. Nothing about it is split, so there is no
        // separator to name — the one case where "none" is the honest answer.
        separator = NO_SEPARATOR,
    )
}

private suspend fun parseText(
    args: Args,
    imports: ImportRepository,
    source: String,
    title: String?,
): ParsedSource {
    if (args.option(FRONT_FIELD_OPTION) != null || args.option(BACK_FIELD_OPTION) != null) {
        throw CliError(
            ExitCode.Usage,
            "--$FRONT_FIELD_OPTION / --$BACK_FIELD_OPTION only apply to an .apkg, which carries " +
                "named fields. A text file's first two columns are its card, and --separator is " +
                "how you say where they split.",
        )
    }
    val text = readSource(source)
    val read = imageColumnRows(text)
    val draft = if (read.notes != null) {
        imports.parseBulkNotes(read.notes, suggestedTitle = title)
    } else {
        imports.parseBulk(text, args.option("separator")?.let(::separatorNamed), suggestedTitle = title)
    }.getOrElse { throw asCliError(it) }
    return ParsedSource(
        draft = draft,
        droppedColumns = read.extraColumns,
        format = ImportFormat.Text,
        apkg = null,
        // The image-column reader splits on tabs — that is what makes it a four-column format —
        // so it says so, rather than borrowing the `.apkg` answer for want of one of its own.
        separator = if (read.notes != null) TAB_SEPARATOR else draft.separator::class.simpleName.orEmpty().lowercase(),
    )
}

/**
 * Say what the pictures are about to cost, before they are spent.
 *
 * On stderr in both modes, because there is nothing to do about it afterwards: the homeserver has
 * no endpoint reporting how much of the 1 GB allowance is left (Architecture.md §8.5), a 507 is
 * terminal, and re-hosting and compaction both *consume* quota rather than reclaiming any. The
 * number is large for a reason worth stating in the same breath — the CLI cannot re-encode an
 * image (see `ApkgBlobs`), so what an Anki deck carries is what it uploads.
 */
internal fun warnAboutMediaSpend(summary: ApkgSummary, onNote: (String) -> Unit) {
    if (summary.images.imported == 0) return
    onNote(
        "This .apkg carries ${summary.images.imported} pictures, ${formatBytes(summary.images.bytes)} " +
            "of them, and `loopky` uploads them at full resolution — it ships no image codec, so " +
            "it cannot shrink them the way the apps do. That is spent against a 1 GB homeserver " +
            "quota nothing can read back. `--dry-run` reports this without writing anything.",
    )
}

private fun readSource(source: String): String = when (source) {
    "-" -> System.`in`.readBytes().toString(Charsets.UTF_8)
    else -> File(source).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        ?: throw CliError(ExitCode.BadInput, "No such file: $source")
}

/**
 * The rows of a tab-separated file that carries image columns, or null when it does not.
 *
 * `front <TAB> back <TAB> front_image_url <TAB> back_image_url` is a format this client defines,
 * so splitting it on tabs is reading a format rather than reimplementing spec §6. The rows then go
 * through `parseBulkNotes`, which is the same body as `parseBulk` minus the splitting step —
 * dedupe, the caps, truncation reporting and the drop-incomplete policy are shared. That entry
 * point exists because dedupe *renumbers* rows, so a picture cannot be re-attached afterwards by
 * the index it was read at.
 *
 * It engages only when **every** non-blank line has at least three tab-separated fields *and*
 * every non-empty image column holds an http(s) URL. Both halves are needed. The first keeps one
 * stray third column in a two-column file from being read as a format; the second keeps a
 * three-column Anki export — Front / Back / Example sentence, a very common shape — from
 * publishing every card with `MediaRef.Image(url = "una manzana roja")`, which both apps then try
 * to load as a picture while the column's real content is lost and `--json` reports success.
 *
 * A file that fails either test is ordinary text and falls through to the shared parser, which is
 * the right answer here — unlike `card add --from-file`, where the four-column TSV is what the
 * user explicitly asked for and a non-URL is an error.
 *
 * Image columns are worth having from day one because since #167 a card image can be a remote ref
 * — a URL, no bytes on the wire, no media quota spent — and neither `.apkg` nor
 * TSV-through-the-parser can say "this side has text *and* a picture" (#54, finding 3).
 */
private fun imageColumnRows(text: String): ImageColumnRead {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.isEmpty()) return ImageColumnRead(null, extraColumns = false)
    val rows = lines.map { it.split('\t') }
    if (rows.any { it.size < IMAGE_FORMAT_MIN_FIELDS }) return ImageColumnRead(null, extraColumns = false)
    // Three-plus columns, but not addresses: ordinary text with a column the shared parser will
    // drop. Declining is right; declining *quietly* is not, so the caller is told.
    if (rows.any { !it.imageColumnsAreUrls() }) return ImageColumnRead(null, extraColumns = true)
    return ImageColumnRead(
        notes = rows.map { fields ->
            BulkNote(
                front = fields[FRONT_COLUMN].trim(),
                back = fields[BACK_COLUMN].trim(),
                frontImage = fields.imageAt(FRONT_IMAGE_COLUMN),
                backImage = fields.imageAt(BACK_IMAGE_COLUMN),
            )
        },
        extraColumns = false,
    )
}

/**
 * What reading a file as the image format found.
 *
 * [extraColumns] is the case that has to be *reported* rather than merely handled: a file whose
 * every line has three or more tab fields, none of them URLs, is ordinary text — and spec §8's
 * parser keeps fields 0 and 1 and drops the rest. So the import succeeds while each card quietly
 * loses a column. That is a quieter version of the loss `--json` exists to make visible: not fewer
 * cards, less of each card. Nothing here changes what is parsed — diverging from the app's parser
 * is the one thing this command must not do — only what is said about it.
 */
private class ImageColumnRead(val notes: List<BulkNote>?, val extraColumns: Boolean)

/** An empty image column says nothing either way; a filled one has to look like an address. */
private fun List<String>.imageColumnsAreUrls(): Boolean =
    listOf(FRONT_IMAGE_COLUMN, BACK_IMAGE_COLUMN).all { column ->
        getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() }?.looksLikeImageUrl() ?: true
    }

private fun List<String>.imageAt(column: Int): DraftCardImage? =
    getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() }?.let { DraftCardImage(url = it) }

private fun separatorNamed(name: String): Separator = when (name.lowercase()) {
    "auto" -> Separator.Auto
    "tab" -> Separator.Tab
    "comma" -> Separator.Comma
    "semicolon" -> Separator.Semicolon
    "pipe" -> Separator.Pipe
    "dash", "emdash" -> Separator.EmDash
    "colon" -> Separator.Colon
    "blank", "blankline" -> Separator.BlankLine
    "markdown", "table" -> Separator.MarkdownTable
    else -> throw CliError(
        ExitCode.Usage,
        "Unknown --separator '$name'. One of: auto, tab, comma, semicolon, pipe, dash, colon, " +
            "blank, markdown.",
    )
}

/** What a source that was genuinely never split reports. Today that is an `.apkg` and nothing else. */
private const val NO_SEPARATOR = "none"

private const val TAB_SEPARATOR = "tab"

private const val FRONT_COLUMN = 0
private const val BACK_COLUMN = 1
private const val FRONT_IMAGE_COLUMN = 2
private const val BACK_IMAGE_COLUMN = 3

/** A file with fewer fields than this on any line is ordinary text for the shared parser. */
private const val IMAGE_FORMAT_MIN_FIELDS = 3
