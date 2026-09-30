package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.isRenderableImageUrl
import kotlinx.serialization.Serializable

/**
 * What the CLI can tell an agent about a picture it will never fetch.
 *
 * Every other client puts a human in front of the image before it is stored — the sheet's Done
 * button waits on a preview that actually loaded. `loopky` has no such moment: it takes a URL,
 * writes it into a card, and reports success, so a deck of 200 cards can be built entirely out of
 * addresses that answer 403. That is not hypothetical; it is how this file came to exist.
 *
 * Fetching them is not the answer. It would make `card add` a crawler, turn a one-second write
 * into a hundred round trips against hosts that rate-limit, and still say nothing about the ones
 * that answer 200 today and 403 tomorrow. So the split here is by what a string can be *known* to
 * be wrong about, without asking anyone:
 *
 * - **[requireRenderableImageUrl] refuses** what can never render on any device Loopky ships for.
 * - **[imageUrlAdvice] warns** about a host rule that is real, checkable and currently being
 *   broken — on stderr, never fatal, because a host's rules are theirs to change and a stale
 *   check must not be able to fail somebody's import.
 */

/**
 * A picture a rule can call wrong **without asking any host**, and why.
 *
 * It rides in `--json` as `image_advice` rather than in `image_checks`, which is documented as
 * what `--check-images` found: this runs on every import, flag or no flag, and folding an
 * always-on finding into the opt-in flag's field would change that field's meaning under the same
 * schema version. A sibling array adds one, which is allowed (#257).
 */
@Serializable
data class ImageAdvice(
    val url: String,
    /**
     * Every card and side this picture is on, as the stderr note names them:
     * `["Card 201 front image", "Card 340 front image"]`.
     *
     * A list because the advice is grouped **by URL**, the way `--check-images` asks about one.
     * A picture on forty cards is one finding repeated forty times otherwise, each carrying the
     * same multi-line paragraph — which is the redundancy this whole change set exists to remove,
     * reappearing in the array that replaced it. The per-card labels are still worth having, so
     * they are kept rather than deduplicated away.
     */
    val where: List<String>,
    /** The whole note, newlines included — a rewritten address is part of it. */
    val advice: String,
)

/**
 * The one call for an image URL arriving from outside: refuses what can never render, and notes
 * what is merely likely to be wrong.
 *
 * Both halves at one call site because they are one question — "is this picture going to appear" —
 * asked at the only moment anyone can still act on the answer. [onNote] defaults to stderr, which
 * is where every other advisory in this client goes and is what keeps `--json`'s stdout a clean
 * machine channel; it is a parameter so the behaviour is testable without capturing a stream.
 *
 * The **immediate** form, for the one caller whose result has nowhere to carry advice: `deck edit`
 * takes a single `--cover-url`, so neither the cap nor the burial [ImageAdviceLog] exists for can
 * apply, and its result shape is `deck show`'s. Everything that writes cards uses the log.
 */
internal fun String.checkedImageUrl(where: String, onNote: (String) -> Unit = System.err::println): String {
    requireRenderableImageUrl(where)
    imageUrlAdvice(this)?.let { onNote("loopky: $where — $it") }
    return this
}

/**
 * [url], or a [CliError] naming why it could never render.
 *
 * The scheme is the whole of it. Android blocks cleartext at targetSdk 28+, iOS ATS does the same,
 * so an `http://` ref is a card whose picture cannot appear on either client — and refusing it
 * here is the last point where the person who typed it is still around to fix it.
 */
internal fun String.requireRenderableImageUrl(where: String): String {
    if (isRenderableImageUrl()) return this
    val why = when {
        startsWith("http://", ignoreCase = true) ->
            "it is an http:// address. Android and iOS both refuse cleartext, so the picture " +
                "would never render on either — use https://."

        any { it.isWhitespace() } -> "it contains a space, which no address can."
        else -> "it must be an https:// URL."
    }
    throw CliError(ExitCode.BadInput, "$where holds \"${take(IMAGE_URL_EXCERPT)}\" — $why")
}

/**
 * Notes worth putting on stderr about [url], or null when there is nothing to say.
 *
 * Two rules, and both come from real decks rather than from what a host's documentation says.
 *
 * **Thumbnail widths.** Wikimedia serves thumbnails at a fixed set of widths only and answers
 * `400, Use thumbnail sizes listed on https://w.wiki/GHai` for every other one. An agent writes
 * `320px-` or `800px-` as readily as `250px-` — they all look equally plausible, and four out of
 * five are a blank card on both clients.
 *
 * **File types neither client decodes.** A Wikipedia lead image is not necessarily a picture:
 * `Dente` and `Tostapane` resolve to `.stl` and `.tiff`, `Rotonda` to `.webm`, and every flag and
 * colour swatch to `.svg`. All are valid `upload.wikimedia.org` addresses that 200, and all are
 * the same blank card — Android decodes with Coil, which ships no SVG decoder here, and iOS with
 * `UIImage`, which decodes neither.
 *
 * That second rule **inverts the first for an SVG**, which is why they are one function rather
 * than two. "Drop the `/thumb/` segment for the original, which is always served" is right for a
 * raster file and exactly backwards for a vector one: the original is `image/svg+xml` and the
 * thumbnail is the only rendered raster there is.
 */
internal fun imageUrlAdvice(url: String): String? {
    val notes = listOfNotNull(undecodableFormatAdvice(url), thumbnailWidthAdvice(url))
    return notes.joinToString("\n").takeIf { it.isNotEmpty() }
}

/**
 * The thumbnail is the answer for a vector or a non-web raster, not the problem — the reverse of
 * every other line of advice here.
 *
 * Only the `.svg` rewrite is spelled out as a URL. Wikimedia does render `.tif` and `.webm` under
 * `/thumb/` too, but with prefixes of their own (`lossy-page1-`, a frame marker), and an address
 * invented here that 404s is worse than a sentence pointing at the file page.
 */
private fun undecodableFormatAdvice(url: String): String? {
    val extension = url.servedExtension() ?: return null
    if (extension !in UNDECODABLE_EXTENSIONS) return null

    val rewrite = url.asWikimediaSvgThumbnail()
    return "$url\n" +
        "  .$extension is not something either app can decode — Android's loader ships no SVG " +
        "decoder and iOS decodes neither vectors nor these, so this card's picture will be " +
        "blank on both.\n" +
        if (rewrite != null) {
            "  Wikimedia renders a raster thumbnail of it, and for a vector that is the only " +
                "raster there is — the /thumb/ advice below is inverted here. Use:\n  $rewrite"
        } else {
            "  Use an address that serves a JPEG, PNG or WebP."
        }
}

/**
 * The same URL as a `500px` PNG thumbnail, when it is a Wikimedia SVG original.
 *
 * The shape is fixed: `/wikipedia/<project>/<a>/<ab>/Name.svg` becomes
 * `/wikipedia/<project>/thumb/<a>/<ab>/Name.svg/500px-Name.svg.png`. Null for anything else,
 * including a URL that already has a `/thumb/` segment.
 */
private fun String.asWikimediaSvgThumbnail(): String? {
    if (!contains(WIKIMEDIA_UPLOAD_HOST, ignoreCase = true)) return null
    if (contains(THUMB_SEGMENT)) return null
    val name = substringAfterLast('/')
    if (!name.endsWith(SVG_EXTENSION, ignoreCase = true)) return null
    val directory = substringBeforeLast('/')
    // The two hash directories the file sits in; `thumb/` goes in front of them, not of the file.
    val hashes = directory.split('/').takeLast(WIKIMEDIA_HASH_SEGMENTS)
    if (hashes.size != WIKIMEDIA_HASH_SEGMENTS) return null
    val project = directory.dropLast(hashes.joinToString("/").length + 1)
    return "$project/thumb/${hashes.joinToString("/")}/$name/" +
        "${WIKIMEDIA_DEFAULT_THUMB_WIDTH}px-$name.png"
}

/**
 * The extension the address actually ends in — **the last one, never one in the middle of the
 * path**.
 *
 * Commons renders a TIFF or a PDF source to a raster thumbnail and keeps the source name in both
 * a directory segment and the file stem, so `.../Cell.tif/lossy-page1-500px-Cell.tif.jpg` serves
 * `image/jpeg` from an address containing `.tif` twice. Judging by "does `.tif` appear" flagged
 * that as undecodable while leaving the exact SVG analogue — `.../Sign.svg/500px-Sign.svg.png` —
 * alone, which is a rule an agent cannot learn and a false finding either way (#257, item 3).
 * The final extension is what the host serves, and it is the only part of the string that says so.
 */
private fun String.servedExtension(): String? = substringBefore('?').substringBefore('#')
    .substringAfterLast('/')
    .substringAfterLast('.', "")
    .lowercase()
    .takeIf { it.isNotEmpty() }

/**
 * The file the address is *of*: the last segment for an original, and for a thumbnail the segment
 * before the `NNNpx-` one, which is the source file `/thumb/` was asked to render.
 *
 * The distinction is the whole of the SVG case. `.../thumb/0/03/Flag.svg/500px-Flag.svg.png` ends
 * in `.png` and is a picture; what decides whether dropping `/thumb/` would help is the `.svg` in
 * the middle.
 */
private fun String.sourceFileName(): String? {
    val segments = substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
    if (segments.size < 2) return null
    val last = segments.last()
    return if (contains(THUMB_SEGMENT) && PX_MARKER in last) segments[segments.size - 2] else last
}

/**
 * A note about [url]'s thumbnail width, or null.
 *
 * The original file is always served, which is why that is what the advice names for a raster: it
 * is the answer that cannot go stale. For a vector it is the wrong answer, so the line about
 * dropping `/thumb/` is withheld and only the width list is offered.
 */
private fun thumbnailWidthAdvice(url: String): String? {
    if (!url.isWikimediaThumbnail()) return null
    val width = url.wikimediaThumbWidth() ?: return null
    if (width in WIKIMEDIA_THUMB_WIDTHS) return null
    val rendersAVector = url.sourceFileName()?.endsWith(SVG_EXTENSION, ignoreCase = true) == true
    return "$url\n" +
        "  Wikimedia serves thumbnails only at ${WIKIMEDIA_THUMB_WIDTHS.joinToString(", ")} px " +
        "and answers 400 for ${width}px, so this card's picture will be blank on both apps.\n" +
        if (rendersAVector) {
            "  Use one of those widths. Do not drop the /thumb/ segment here — the original is an " +
                "SVG, which neither app decodes."
        } else {
            "  Use one of those widths, or drop the /thumb/ segment and the NNNpx- prefix to get " +
                "the full-size original on $WIKIMEDIA_UPLOAD_HOST, which is always served."
        }
}

/**
 * The `NNN` of the trailing `.../NNNpx-Name.jpg` segment, or null when the URL is not that shape.
 *
 * Read from the **last** path segment rather than by searching the whole URL: `px-` occurs in
 * plenty of file names, and a thumbnail's width is only ever in its own segment.
 *
 * The digits are taken from the *end* of what precedes `px-`, because a rendered non-raster
 * source carries a marker in front of them — `lossy-page1-500px-Cell.tif.jpg`, `page1-`, a frame
 * number. Parsing the whole prefix as a number gave those URLs no width at all, so the width rule
 * silently did not apply to any of them.
 */
private fun String.wikimediaThumbWidth(): Int? {
    val prefix = substringAfterLast('/').substringBefore(PX_MARKER, missingDelimiterValue = "")
    val digits = prefix.takeLastWhile { it.isDigit() }
    if (digits.isEmpty()) return null
    // The digits either begin the segment or follow a render marker's hyphen. Without that,
    // `Foo2px-bar.jpg` reads as a 2px thumbnail and draws a warning about a width nobody asked
    // for — a new false positive in a rule the README tells agents to treat as authoritative.
    val before = prefix.dropLast(digits.length)
    if (before.isNotEmpty() && !before.endsWith('-')) return null
    return digits.toIntOrNull()
}

/**
 * The widths `upload.wikimedia.org` served on 2026-09-04, measured across 35 candidates, plus
 * `1920` — measured on 2026-09-06 against `Blausen_0463_HeartAttack.png`, where 1920 answers 200
 * and 2560 answers 400 (#257, item 4).
 *
 * A snapshot, and treated as one — this drives a warning and never a refusal, so the cost of the
 * list going stale is a note nobody needed rather than an import that will not run. The direction
 * that costs something is a width missing from here: an agent takes the list as authoritative and
 * rewrites a working URL.
 */
private val WIKIMEDIA_THUMB_WIDTHS = listOf(120, 250, 330, 500, 960, 1280, 1920)

/**
 * What the two clients cannot turn into a picture, by extension.
 *
 * Every one of these was a real Wikipedia lead image in one 855-card deck (#229): flags and colour
 * swatches are `.svg`, and `Dente`, `Piede`, `Tostapane`, `Rotonda` and `Tastiera` resolve to
 * `.stl`, `.tiff`, `.webm` and `.ogv`. Extensions only — a host serving a PNG from a URL ending
 * `.php` is not knowable from the string, and `--check-images` is the tool for that.
 */
private val UNDECODABLE_EXTENSIONS = setOf("svg", "tif", "tiff", "webm", "ogv", "ogg", "stl", "pdf", "djvu", "xcf")

/** What a rewritten SVG thumbnail asks for. In [WIKIMEDIA_THUMB_WIDTHS], and a card-sized one. */
private const val WIKIMEDIA_DEFAULT_THUMB_WIDTH = 500

/** `commons/9/9a/` — the two hash directories every upload sits under. */
private const val WIKIMEDIA_HASH_SEGMENTS = 2

/**
 * A Wikimedia thumbnail address, on either host it can arrive on.
 *
 * `thumb.wikimedia.org` is what the `imageinfo` API's `thumburl` hands back, and it serves the
 * same picture — but a rule written for `upload.` alone silently did not apply to any of them,
 * which is every URL an agent gets from that API (#257, smaller notes).
 */
private fun String.isWikimediaThumbnail(): Boolean =
    contains(THUMB_SEGMENT) &&
        (contains(WIKIMEDIA_UPLOAD_HOST, ignoreCase = true) || contains(WIKIMEDIA_THUMB_HOST, ignoreCase = true))

private const val WIKIMEDIA_UPLOAD_HOST = "upload.wikimedia.org"

private const val WIKIMEDIA_THUMB_HOST = "thumb.wikimedia.org"
private const val THUMB_SEGMENT = "/thumb/"
private const val PX_MARKER = "px-"
private const val SVG_EXTENSION = ".svg"
private const val IMAGE_URL_EXCERPT = 60

/**
 * Every remote picture this draft carries, labelled by the card and side it is on.
 *
 * A blob has no URL to say anything about — an `.apkg`'s pictures are bytes, already read.
 */
internal suspend fun ImportRepository.draftImageUrls(): List<Pair<String, String>> =
    keptRows().flatMapIndexed { index, row ->
        listOfNotNull(
            rowImage(row.index, isFront = true)?.url?.let { "Card ${index + 1} front image" to it },
            rowImage(row.index, isFront = false)?.url?.let { "Card ${index + 1} back image" to it },
        )
    }

/**
 * Somewhere to put static picture advice while a command is still deciding what it will write.
 *
 * Collected rather than printed as each URL is met, for two reasons that only appear at scale.
 * Printed per row it lands *before* `--check-images`'s block and scrolls away — and it is the more
 * valuable half, since it is what a string is *known* to be wrong about rather than what a host
 * said this minute (#257, item 1). Printed at all, it never reaches `--json`, the channel this
 * client calls its verification channel (#261 review).
 *
 * One log per command, emptied into the result and onto stderr once everything is known. The
 * **refusal** is not deferred: [requireRenderableImageUrl] still throws where the URL is met,
 * because it ends the command and nothing can bury that.
 */
internal class ImageAdviceLog {
    private val entries = mutableListOf<Triple<String, String, String>>()

    /**
     * One row per distinct URL, in the order each was first met, carrying every place it appears.
     *
     * Grouped rather than one row per occurrence, which is the same property `checkImageUrls`
     * states for its own findings: a picture on forty cards is one thing to fix.
     */
    val advice: List<ImageAdvice>
        get() = entries.groupBy { (url, _, _) -> url }
            .map { (url, group) ->
                ImageAdvice(url = url, where = group.map { it.second }, advice = group.first().third)
            }

    /** [url], refused if it could never render and noted if it merely probably will not. */
    fun checked(url: String, where: String): String {
        url.requireRenderableImageUrl(where)
        imageUrlAdvice(url)?.let { entries += Triple(url, where, it) }
        return url
    }

    /** The same over the `where to url` pairs an import's draft hands over. */
    fun checkedAll(images: List<Pair<String, String>>) {
        images.forEach { (where, url) -> checked(url, where) }
    }
}

/**
 * An [ImageAdviceLog] on stderr, in one labelled block, after everything the network had to say.
 *
 * Capped like `--check-images`'s two buckets, and for the same reason — each entry is several
 * lines, so a deck of 1210 bad thumbnail widths would bury the block that was just capped to stay
 * readable. The cap is only safe because every row also travels in `--json` as `image_advice`.
 */
internal fun List<ImageAdvice>.reportStaticImageAdvice(onNote: (String) -> Unit) {
    if (isEmpty()) return
    onNote("loopky: $size picture URL(s) are knowably wrong without asking any host:")
    take(MAX_REPORTED_ADVICE).forEach { onNote("loopky:   ${it.whereSummary()} — ${it.advice}") }
    if (size > MAX_REPORTED_ADVICE) {
        onNote("loopky:   … and ${size - MAX_REPORTED_ADVICE} more — every one is in --json image_advice.")
    }
}

/**
 * The places one picture appears, for a person: a few of them, then a count.
 *
 * The list itself can be as long as the deck — a shared cover on 1210 cards — and the point of the
 * line is the picture, not the census.
 */
private fun ImageAdvice.whereSummary(): String = when {
    where.size <= MAX_REPORTED_WHERE -> where.joinToString(", ")
    else -> where.take(MAX_REPORTED_WHERE).joinToString(", ") + " and ${where.size - MAX_REPORTED_WHERE} more"
}

/** Entries printed on stderr before the rest are left to `--json`. Matches `--check-images`'s cap. */
private const val MAX_REPORTED_ADVICE = 20

private const val MAX_REPORTED_WHERE = 3
