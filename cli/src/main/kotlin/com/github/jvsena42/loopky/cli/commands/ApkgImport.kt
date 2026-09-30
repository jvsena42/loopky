package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.data.anki.ApkgException
import com.github.jvsena42.loopky.data.anki.ApkgFailure
import com.github.jvsena42.loopky.data.anki.ApkgFieldMapping
import com.github.jvsena42.loopky.data.anki.ApkgImport
import com.github.jvsena42.loopky.data.anki.ApkgReader
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * `.apkg` — an Anki export — as an input to `loopky import`.
 *
 * The reader itself is shared with both apps (`ApkgReader`, `ApkgCollection.kt`); what lives here is
 * the part a terminal needs and a picker screen does not. Three things, each because of a way this
 * import goes wrong quietly:
 *
 * - **The field picker, headlessly.** Anki decks routinely carry fields called "Field 3", and 9000
 *   Spanish Sentences imported 9,213 cards reading `2528426` → `2760065` because its first two
 *   fields are database ids (#96). `chooseDefaultFields` usually gets this right; [ApkgFieldMapping]
 *   is how you disagree, and `--dry-run` is how you look first.
 * - **The drop accounting.** 1,458 notes in and 1,338 cards out used to be explained by "1
 *   duplicate", because the other 119 were dropped inside the reader. [ApkgSummary] carries them.
 * - **What a run will spend.** This is the one import path that **uploads bytes**, against a 1 GB
 *   quota with no endpoint reporting what is left (§8.5) — and at full resolution (see [ApkgBlobs]),
 *   so the number has to be on screen before it is spent.
 */

/** Which reader an `import` operand goes to. */
internal enum class ImportFormat { Text, Apkg }

/**
 * Extension first, then content — the same order [readCardFile] picks its format in: a caller that
 * has to remember a `--apkg` flag will one day not, and an `.apkg` read as text is a deck whose
 * fronts are fragments of a SQLite header.
 *
 * The content half, [ApkgReader.canRead], is deliberately the weaker of the two: a file *named*
 * `.apkg` goes to the `.apkg` reader even when it is not a zip, so the failure names the format the
 * user asked for rather than reporting "nothing importable" about a file they know is an Anki deck.
 */
internal fun detectImportFormat(source: String, header: () -> ByteArray): ImportFormat = when {
    source.endsWith(".apkg", ignoreCase = true) -> ImportFormat.Apkg
    ApkgReader.canRead(header()) -> ImportFormat.Apkg
    else -> ImportFormat.Text
}

/** The first bytes of [path], or empty when it cannot be opened — which the caller reports. */
internal fun fileHeader(path: String): ByteArray =
    runCatching {
        File(path).inputStream().use { stream ->
            val buffer = ByteArray(HEADER_BYTES)
            val read = stream.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

/** Four is all [ApkgReader.canRead] looks at; a few more cost nothing and read better in a dump. */
private const val HEADER_BYTES = 8

/** An `.apkg` read, plus the one thing the reader does not report: how many bytes its blobs are. */
internal class ApkgRead(val import: ApkgImport, val imageBytes: Long)

/**
 * Read [path], resolving `--front-field` / `--back-field` against the deck's own field names.
 *
 * At most two passes over the archive, and the second only when fields were named — field *names*
 * are knowable only by reading the collection, so a mapping given as a name cannot be resolved
 * before the first read. The probe pass throws its blobs away, so naming a field costs a re-read
 * rather than a second copy of the deck's media in heap.
 *
 * [keepBytes] is false for a dry run, which measures the pictures without holding them.
 */
internal suspend fun readApkg(path: String, args: Args, keepBytes: Boolean): ApkgRead {
    val named = args.option(FRONT_FIELD_OPTION) != null || args.option(BACK_FIELD_OPTION) != null
    if (!named) {
        val blobs = ApkgBlobs(keepBytes)
        return ApkgRead(readApkgWith(path, mapping = null, blobs = blobs), blobs.bytes)
    }

    val probe = readApkgWith(path, mapping = null, blobs = ApkgBlobs(keepBytes = false))
    val mapping = resolveFieldMapping(args, probe.fieldNames, probe.mapping)
    val blobs = ApkgBlobs(keepBytes)
    return ApkgRead(readApkgWith(path, mapping, blobs), blobs.bytes)
}

private suspend fun readApkgWith(
    path: String,
    mapping: ApkgFieldMapping?,
    blobs: ApkgBlobs,
): ApkgImport = ApkgReader
    .readNotes(path, mapping) { bytes, mime -> blobs.take(bytes, mime) }
    .getOrElse { throw apkgFailure(path, it) }

/**
 * The archive's pictures on their way through the reader: measured always, kept only when they are
 * about to be uploaded.
 *
 * **Nothing is compressed here, and that is a packaging constraint rather than an oversight.** The
 * apps run every imported picture through `MediaProcessor` (1024 px, JPEG q80). The desktop
 * implementation reaches `javax.imageio` → `java.awt`, which `native-image` cannot fold into the
 * executable: it emits five JDK `.so`s beside the binary and `loopky` stops being one file (#210,
 * `:cli:checkNativeImageIsOneFile`). The Koin binding here is `PassThroughMediaProcessor`, and this
 * deliberately does not reach for it at all — going through the graph for a call that must never do
 * anything is an invitation to bind something that does.
 *
 * So an `.apkg`'s media costs an order of magnitude more from a terminal than from a phone, which is
 * why [ApkgImagesView.bytes] is reported before the spend and `import` warns on stderr.
 */
internal class ApkgBlobs(private val keepBytes: Boolean) {

    /** Raw bytes of the distinct blobs the reader pulled out of the archive. */
    var bytes: Long = 0L
        private set

    fun take(raw: ByteArray, mime: String): DraftCardImage {
        bytes += raw.size
        return DraftCardImage(bytes = if (keepBytes) raw else EMPTY, mime = mime)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * Which two fields to import, from `--front-field` / `--back-field`.
 *
 * Either may be given alone: "the front is right and the back is wrong" is the common half-correct
 * case. [chosen] fills in whichever was not named.
 *
 * A value is matched as a **name** first and only then as a 1-based index, so a deck whose fields are
 * called "1" and "2" can still be addressed by name. Indices are 1-based because that is what the
 * deck shows: an unnamed field arrives labelled "Field 1", and a surface where `Field 1` is selected
 * by `--front-field 0` is a trap.
 */
internal fun resolveFieldMapping(
    args: Args,
    names: List<String>,
    chosen: ApkgFieldMapping,
): ApkgFieldMapping {
    val front = resolveField(args.option(FRONT_FIELD_OPTION), FRONT_FIELD_OPTION, names) ?: chosen.frontOrd
    val back = resolveField(args.option(BACK_FIELD_OPTION), BACK_FIELD_OPTION, names) ?: chosen.backOrd
    if (front == back) {
        throw CliError(
            ExitCode.Usage,
            "--$FRONT_FIELD_OPTION and --$BACK_FIELD_OPTION both name ${fieldLabel(names, front)}. " +
                "A card needs two different fields. ${availableFields(names)}",
        )
    }
    return ApkgFieldMapping(frontOrd = front, backOrd = back)
}

private fun resolveField(value: String?, option: String, names: List<String>): Int? {
    val wanted = value?.trim() ?: return null
    val matches = names.withIndex().filter { (_, name) -> name.trim().equals(wanted, ignoreCase = true) }
    if (matches.size > 1) {
        throw CliError(
            ExitCode.Usage,
            "This deck has ${matches.size} fields called \"$wanted\", so --$option cannot tell " +
                "which one you mean. Use its number instead: " +
                matches.joinToString(", ") { (index, _) -> "${index + 1}" } + ".",
        )
    }
    matches.singleOrNull()?.let { (index, _) -> return index }

    // Only after the name test: a field genuinely named "2" is addressable, and the number is the
    // fallback rather than the primary spelling.
    val ordinal = wanted.toIntOrNull()
    if (ordinal != null && ordinal in 1..names.size) return ordinal - 1

    throw CliError(
        ExitCode.Usage,
        "--$option '$wanted' is neither a field of this deck nor a number between 1 and " +
            "${names.size}. ${availableFields(names)}",
    )
}

private fun fieldLabel(names: List<String>, ord: Int): String =
    "field ${ord + 1} (\"${names.getOrNull(ord).orEmpty()}\")"

private fun availableFields(names: List<String>): String =
    "This deck's fields are: " +
        names.withIndex().joinToString(", ") { (index, name) -> "${index + 1} \"$name\"" } +
        ". `loopky import <file> --dry-run --json` shows a sample value for each."

internal const val FRONT_FIELD_OPTION = "front-field"
internal const val BACK_FIELD_OPTION = "back-field"

/**
 * A failed `.apkg` read, as something a caller can act on.
 *
 * The three [ApkgFailure] reasons keep **one** exit code — [ExitCode.BadInput] is true of all three.
 * What they do not share is the *advice*: a zstd collection, an export holding only Anki's
 * compatibility stub, and a corrupt zip used to surface as one string, which sent people hunting for
 * a format problem they might not have.
 */
private fun apkgFailure(source: String, error: Throwable): CliError =
    when ((error as? ApkgException)?.reason) {
        ApkgFailure.UnsupportedFormat -> CliError(
            ExitCode.BadInput,
            "$source holds no collection this build can read. Anki's newest export packs it as " +
                "collection.anki21b, which is zstd-compressed, and `loopky` ships no " +
                "decompressor for it. Re-export from Anki with \"Support older Anki versions\" " +
                "ticked, or use File > Export > Notes in Plain Text and import the .txt.",
        )

        ApkgFailure.LegacyStubOnly -> CliError(
            ExitCode.BadInput,
            "$source holds only Anki's legacy compatibility stub — one note reading \"Please " +
                "update to the latest Anki version\". The deck itself is in a collection this " +
                "build did not find, which is the zstd collection.anki21b above. Re-export with " +
                "\"Support older Anki versions\" ticked, or as Notes in Plain Text.",
        )

        ApkgFailure.Unreadable, null -> CliError(
            ExitCode.BadInput,
            "$source could not be opened as an .apkg: " +
                (error.message ?: error::class.simpleName ?: "unreadable") +
                ". An .apkg is a zip around a SQLite collection; a partial download is the usual " +
                "cause.",
        )
    }

/** One of the deck's fields, as `--front-field` / `--back-field` will accept it back. */
@Serializable
data class ApkgFieldView(
    /** 1-based, matching the labels an unnamed field is given ("Field 1"). */
    val index: Int,
    val name: String,
    /**
     * A real value out of the deck, scanned over a prefix of the notes rather than taken from the
     * first one. A name alone is not enough to choose between "Field 3" and "Field 4".
     */
    val sample: String,
)

@Serializable
data class ApkgMappingView(
    @SerialName("front_index") val frontIndex: Int,
    @SerialName("front_name") val frontName: String,
    @SerialName("back_index") val backIndex: Int,
    @SerialName("back_name") val backName: String,
)

/** Notes that never became cards, by reason. See `ApkgDropped` — reported, never subtracted. */
@Serializable
data class ApkgDroppedView(
    /** Neither chosen field held anything. */
    val empty: Int,
    /** One side was there and the other was not. */
    @SerialName("half_empty") val halfEmpty: Int,
    /** A picture the archive named but does not contain. */
    @SerialName("missing_media") val missingMedia: Int,
    val total: Int,
)

@Serializable
data class ApkgImagesView(
    /** Distinct pictures pulled out of the archive and attached to a card side. */
    val imported: Int,
    /** Pictures left behind at the reader's per-deck ceiling of 500. */
    val skipped: Int,
    /**
     * Raw bytes of those pictures — the ceiling on what publishing this deck spends against the 1 GB
     * quota, since the CLI uploads them **uncompressed** (see [ApkgBlobs]). A ceiling rather than an
     * exact figure: a picture on a card that dedupe collapses, or that `--resume` finds already
     * published, is counted here and not uploaded.
     */
    val bytes: Long,
)

/**
 * What the `.apkg` reader found, in the `--json` envelope. On a dry run this is the whole answer; on
 * a real import it travels beside the deck, because an agent cannot look at a summary screen — and
 * every one of these numbers was invisible in the version of this import that produced 1,338 cards
 * from 1,458 notes and called the difference "1 duplicate" (#96).
 */
@Serializable
data class ApkgSummary(
    /** Anki's own name for the deck. Reported, never adopted — `--title` is the CLI's channel. */
    @SerialName("deck_name") val deckName: String? = null,
    /**
     * Anki's own deck description. Reported, never adopted: every AnkiWeb export carries the same
     * "Please see the shared deck page for more info", which is about the listing and not the deck.
     */
    @SerialName("deck_description") val deckDescription: String? = null,
    /** Notes read out of the collection, before any were dropped or a cloze note expanded. */
    @SerialName("note_count") val noteCount: Int,
    val fields: List<ApkgFieldView>,
    val mapping: ApkgMappingView,
    val dropped: ApkgDroppedView,
    val images: ApkgImagesView,
    /**
     * The deck's dominant note type generates more than one card per note. Loopky still writes one
     * card per note — a reverse is a way of studying a card, not a second card — so this becomes the
     * default for `--reverse`, still overridable with `--no-reverse`.
     */
    val reversible: Boolean,
    /** The collection read held notes, but every one was Anki's compatibility placeholder. */
    @SerialName("legacy_stub") val legacyStub: Boolean,
    /**
     * Labels derived from the notes' own tags. Reported, never adopted: a tag is a **public** record
     * indexed network-wide by Nexus (§7.7), and this client does not put one on a deck nobody asked
     * it to. Pass them back as `--tag` if they are right.
     */
    @SerialName("suggested_tags") val suggestedTags: List<String>,
)

internal fun ApkgRead.toSummary(): ApkgSummary {
    val names = import.fieldNames
    return ApkgSummary(
        deckName = import.deckName,
        deckDescription = import.deckDescription,
        noteCount = import.noteCount,
        fields = names.mapIndexed { index, name ->
            ApkgFieldView(
                index = index + 1,
                name = name,
                sample = import.fieldSamples.getOrNull(index).orEmpty(),
            )
        },
        mapping = ApkgMappingView(
            frontIndex = import.mapping.frontOrd + 1,
            frontName = names.getOrNull(import.mapping.frontOrd).orEmpty(),
            backIndex = import.mapping.backOrd + 1,
            backName = names.getOrNull(import.mapping.backOrd).orEmpty(),
        ),
        dropped = ApkgDroppedView(
            empty = import.dropped.empty,
            halfEmpty = import.dropped.halfEmpty,
            missingMedia = import.dropped.missingMedia,
            total = import.dropped.total,
        ),
        images = ApkgImagesView(
            imported = import.imagesImported,
            skipped = import.imagesSkipped,
            bytes = imageBytes,
        ),
        reversible = import.reversible,
        legacyStub = import.isLegacyStub,
        suggestedTags = import.suggestedTags,
    )
}

/** The `.apkg` half of a human-readable report, or nothing at all for a text import. */
internal fun ApkgSummary.describe(): String = buildString {
    appendLine("Anki notes: $noteCount")
    appendLine(
        "Fields: front ${mapping.frontIndex} \"${mapping.frontName}\", " +
            "back ${mapping.backIndex} \"${mapping.backName}\"" +
            if (fields.size > 2) " (of ${fields.size} — --front-field/--back-field to change)" else "",
    )
    if (dropped.total > 0) {
        appendLine(
            "DROPPED ${dropped.total} notes: ${dropped.empty} empty, " +
                "${dropped.halfEmpty} missing a side, ${dropped.missingMedia} missing a picture",
        )
    }
    if (images.imported > 0 || images.skipped > 0) {
        appendLine(
            "Images: ${images.imported} (${formatBytes(images.bytes)} uploaded uncompressed)" +
                if (images.skipped > 0) ", ${images.skipped} left behind at the 500-image cap" else "",
        )
    }
    if (suggestedTags.isNotEmpty()) {
        appendLine("Anki note tags, not applied: ${suggestedTags.joinToString(", ")} (pass --tag to use them)")
    }
    if (reversible) appendLine("This note type is reversed in Anki, so --reverse defaults on.")
}.trimEnd()

/**
 * Bytes as a person reads them. Decimal units, matching how a homeserver quota is quoted ("1 GB"):
 * a number meant to be compared against an allowance must use the allowance's scale.
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= GIGA -> "${bytes / (GIGA / TENTHS) / TENTHS.toDouble()} GB"
    bytes >= MEGA -> "${bytes / (MEGA / TENTHS) / TENTHS.toDouble()} MB"
    bytes >= KILO -> "${bytes / KILO} kB"
    else -> "$bytes B"
}

private const val KILO = 1_000L
private const val MEGA = 1_000_000L
private const val GIGA = 1_000_000_000L

/** One decimal place, by dividing at a tenth of the unit and scaling back. */
private const val TENTHS = 10L
