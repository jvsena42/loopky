package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.DeckView
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.LanguageTags
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.frontBackOf
import com.github.jvsena42.loopky.domain.model.ordForIndex
import com.github.jvsena42.loopky.util.generateId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImportResult(
    val deck: DeckView,
    @SerialName("cards_written") val cardsWritten: Int,
    /** Rows the parser collapsed as duplicates of an earlier row. */
    @SerialName("duplicates_collapsed") val duplicatesCollapsed: Int = 0,
    /** Rows dropped for exceeding the parser's cap. Reported, never silent. */
    val truncated: Int = 0,
    /** Cards already in the deck when `--resume` picked it up, and therefore not written again. */
    val resumed: Int = 0,
    /**
     * Whether `--resume` found a deck to continue, or `null` when `--resume` was not asked for.
     *
     * `resumed: 0` cannot answer this: it is what a legitimate first `--resume` run reports *and*
     * what a typo'd `--title` reports one character from an existing deck — where the second
     * silently publishes a duplicate and spends the quota twice.
     */
    @SerialName("resume_matched") val resumeMatched: Boolean? = null,
    /**
     * Columns past the front and back that the parser dropped, per row. A three-column file whose
     * third column is not a URL is ordinary text, and spec §8 keeps fields 0 and 1 — a loss worth
     * naming rather than leaving for someone to notice in the app.
     */
    @SerialName("columns_dropped") val columnsDropped: Int = 0,
    /**
     * How the file was split: `"tab"`, `"comma"`, … and `"none"` only for a source that was never
     * split at all, which today means an `.apkg`. A four-column TSV reports `"tab"`, because that
     * is what its reader does — see [ParsedSource.separator].
     */
    val separator: String,
    /** `"text"` or `"apkg"`. See [detectImportFormat]. */
    val format: String = ImportFormat.Text.json,
    /** The `.apkg` reader's own accounting, or null for a text import. See [ApkgSummary]. */
    val apkg: ApkgSummary? = null,
    /** What `--check-images` found, and only what is worth reporting. Empty without the flag. */
    @SerialName("image_checks") val imageChecks: List<ImageCheck> = emptyList(),
    /**
     * What is wrong with a picture URL without asking any host — an undecodable format, a
     * thumbnail width Wikimedia does not serve. See [ImageAdvice].
     *
     * Reported whether or not `--check-images` was passed, which is why it is not folded into
     * [imageChecks]: it is also the *more* valuable half, since it is what a string is known to be
     * wrong about rather than what a host happened to answer this minute.
     */
    @SerialName("image_advice") val imageAdvice: List<ImageAdvice> = emptyList(),
)

/**
 * What `--dry-run` reports: everything an import would do, and no deck.
 *
 * A separate shape from [ImportResult] rather than one with a nulled-out deck, so a caller cannot
 * mistake one for the other. It exists mostly for the `.apkg` path, where guessing the field
 * mapping wrong is the likeliest way to publish 9,000 cards of database ids (#96) — this is the one
 * import where verifying after the write is too late.
 *
 * Needs **no session**: requiring a live one to look at a local file would put a sign-in between an
 * agent and the check that stops it spending someone's quota.
 */
@Serializable
data class ImportPreview(
    @SerialName("dry_run") val dryRun: Boolean = true,
    val source: String,
    val format: String,
    /** `--title`, if it was given. Optional here: a preview may be what decides the title. */
    val title: String? = null,
    /** Cards this file would publish, after dedupe and the parser's caps. */
    val cards: Int,
    @SerialName("duplicates_collapsed") val duplicatesCollapsed: Int = 0,
    val truncated: Int = 0,
    @SerialName("columns_dropped") val columnsDropped: Int = 0,
    val separator: String,
    val apkg: ApkgSummary? = null,
    /**
     * What `--check-images` found. Most useful here of anywhere: a dry run is the one moment an
     * agent can still fix 900 addresses before a single card carries one.
     */
    @SerialName("image_checks") val imageChecks: List<ImageCheck> = emptyList(),
    /** The same as [ImportResult.imageAdvice], and worth the most here: nothing is written yet. */
    @SerialName("image_advice") val imageAdvice: List<ImageAdvice> = emptyList(),
)

/** The `--json` spelling of a format, kept beside the enum so the two cannot drift. */
internal val ImportFormat.json: String
    get() = when (this) {
        ImportFormat.Text -> "text"
        ImportFormat.Apkg -> "apkg"
    }

/**
 * Import a deck from a file, or from stdin.
 *
 * The text path is the **app's own parser**: `ImportRepository.parseBulk` runs the spec §6
 * separator rules and §9 edge cases with file-sized caps, so a deck imported here and one imported
 * on a phone are split the same way.
 *
 * An **Anki `.apkg`** takes the same command and the same spine — the shared reader turns it into
 * `BulkNote`s and `parseBulkNotes` carries them through the same dedupe, caps and drop policy. A
 * second verb would have been a second import flow, the one thing #54 rules out. See `ApkgImport.kt`.
 * A tab-separated file with **image columns** takes the other entry point, [imageColumnRows].
 *
 * `--title` is required and beats any inference from the filename.
 */
@Suppress("LongParameterList", "LongMethod")
suspend fun import(
    args: Args,
    imports: ImportRepository,
    decks: DeckRepository,
    cards: CardRepository,
    media: MediaRepository,
    session: Session,
    onProgress: (String) -> Unit,
    /**
     * Something the caller needs to know rather than a counter — reaches stderr even under `--json`.
     * Two notes today: that `--resume` matched no deck, and what an `.apkg`'s pictures are about to
     * spend against a quota nothing can read back. Suppressing either under `--json` withholds it
     * exactly where the mistake is expensive.
     */
    onNote: (String) -> Unit,
): CommandResult {
    val source = args.requireSource()
    val title = args.requireOption("title").trim()
    if (title.isEmpty()) throw CliError(ExitCode.Usage, "--title cannot be empty.")

    val parsed = parseSource(args, imports, source, title, keepImageBytes = true)
    val draft = parsed.draft
    parsed.apkg?.let { warnAboutMediaSpend(it, onNote) }
    // Before anything is built, because `remoteImage` refuses an http:// address by throwing an
    // assertion no classifier recognises — so an unrenderable column in someone's file reached the
    // user as exit 1 "internal" plus a Kotlin message. This is also where the /thumb/ and
    // undecodable-format advice reaches an import at all; only `card add` ever ran it before.
    val images = imports.draftImageUrls()
    val log = ImageAdviceLog()
    log.checkedAll(images)
    // Up here rather than inside `newDeck`/`overlaidWith`, which run after the advice is reported:
    // a cover URL is a picture like any other and belongs in the same block and the same array.
    args.option("cover-url")?.let { log.checked(it, "--cover-url") }
    val resume = resumeState(args, decks, cards, title, onNote)
    // Minted once and threaded down: every card carries its deck's id, so deriving it twice is how
    // a resumed run writes cards addressed to a deck that does not exist.
    val deckId = resume.deck?.id ?: generateId()

    // Both before a byte is written, and the advice after the probe. Inside the `try` it was
    // printed only by a run that got past card building and media upload — so an import that died
    // there, the run most likely to want the advice, showed none of it. Ahead of the upload it
    // also gets the chance to say the pictures are wrong before the quota is spent on them.
    val imageChecks = images.map { it.second }.checkedIfAsked(args, onNote)
    log.advice.reportStaticImageAdvice(onNote)

    // Blobs this invocation wrote, so an aborted publish of a *new* deck can take them back out.
    val uploaded = mutableListOf<MediaRef>()
    val written = try {
        val built = buildCards(imports, draft, resume, deckId, media, uploaded, onProgress)
        val published = if (resume.deck != null) {
            appendMissing(decks, deckId, built, resume.deck.overlaidWith(args), onProgress)
        } else {
            val deck = newDeck(args, draft, session, deckId, built.size)
            decks.publish(deck, built) { progress ->
                onProgress(
                    "${progress.cardsWritten}/${progress.totalCards} cards, " +
                        "${progress.chunksWritten}/${progress.totalChunks} chunks",
                )
            }.getOrElse { throw asCliError(it) }
        }
        Written(published, built.size)
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        // Only for a deck that does not exist yet. Blobs are content-addressed per deck, so on a
        // resumed run the shas this invocation uploaded are the ones the *already published* cards
        // point at — sweeping them would strip the pictures off cards that were fine.
        if (resume.deck == null) sweepUploadedMedia(media, deckId, uploaded, onNote)
        throw error
    }

    imports.clear()
    val published = written.deck
    return result(
        ImportResult(
            deck = published.toView(),
            cardsWritten = written.cards,
            duplicatesCollapsed = draft.duplicatesCollapsed,
            truncated = draft.truncated,
            resumed = resume.alreadyThere.size,
            resumeMatched = if (args.has("resume")) resume.deck != null else null,
            columnsDropped = if (parsed.droppedColumns) 1 else 0,
            separator = parsed.separator,
            format = parsed.format.json,
            apkg = parsed.apkg,
            imageChecks = imageChecks,
            imageAdvice = log.advice,
        ),
        describeImport(written, title, parsed, resume),
    )
}

/**
 * The file to import, refused when it is present but empty.
 *
 * Absent is a usage error — you forgot the operand. `loopky import "" --title x` is not: an empty
 * string is an answer, it can never name a file, and reporting it as anything an agent retries
 * (see `requireUsableOperand`) buys a loop against an input that cannot come right.
 */
private fun Args.requireSource(): String {
    val source = word(1) ?: throw CliError(ExitCode.Usage, "Missing <file>, or - for stdin.")
    if (source.isBlank()) {
        throw CliError(ExitCode.BadInput, "<file> is empty. Give a path, or - to read stdin.")
    }
    return source
}

/**
 * The same numbers as [ImportResult], for a person. Deliberately the *same* numbers rather than a
 * friendlier subset: a loss only one rendering mentions is a loss somebody will miss.
 */
private fun describeImport(
    written: Written,
    title: String,
    parsed: ParsedSource,
    resume: ResumeState,
): String {
    val draft = parsed.draft
    return buildString {
        appendLine("Imported ${written.cards} cards into ${written.deck.id} — $title")
        if (resume.alreadyThere.isNotEmpty()) {
            appendLine("Resumed: ${resume.alreadyThere.size} already present")
        }
        if (draft.duplicatesCollapsed > 0) appendLine("Duplicates collapsed: ${draft.duplicatesCollapsed}")
        if (draft.truncated > 0) appendLine("DROPPED at the parser cap: ${draft.truncated}")
        if (parsed.droppedColumns) {
            appendLine(
                "DROPPED a third column on every row — it held text, not image URLs, so the " +
                    "parser kept only front and back.",
            )
        }
        parsed.apkg?.let { appendLine(it.describe()) }
        append(parsed.describeSeparator())
    }
}

/**
 * The separator line, with the one answer that reads like a failure explained.
 *
 * `Separator: none` on a well-formed file is indistinguishable from a parse that found nothing,
 * and an agent validating its input before a production write cannot tell which it is being told
 * (#257, item 7). Only an `.apkg` reports it now, and only with the reason attached.
 */
private fun ParsedSource.describeSeparator(): String = when (separator) {
    "none" -> "Separator: none — an .apkg stores typed fields, so nothing was split."
    else -> "Separator: $separator"
}

/**
 * `--dry-run`: read the file, report what publishing it would do, write nothing.
 *
 * Takes no [Session] on purpose — see [ImportPreview]. Also where `--title` stops being mandatory:
 * a preview is frequently what tells you what the deck is called.
 */
suspend fun importDryRun(
    args: Args,
    imports: ImportRepository,
    onNote: (String) -> Unit = System.err::println,
): CommandResult {
    val source = args.requireSource()
    val title = args.option("title")?.trim()?.takeIf { it.isNotEmpty() }

    // Nothing is uploaded, so blobs are measured and dropped rather than held: a dry run of a
    // 500-image deck should not need the deck's media in heap to answer how big it is.
    val parsed = parseSource(args, imports, source, title, keepImageBytes = false)
    val draft = parsed.draft
    val cards = imports.keptRows().size
    val images = imports.draftImageUrls()
    val log = ImageAdviceLog()
    log.checkedAll(images)
    // The cover too, as the real run does. Without it the *pre-flight* was the laxer of the two —
    // `import --cover-url http://…` exits 9 and `--dry-run` over the same command exited 0 —
    // on the command whose `--dry-run` is documented as the thing you run first.
    args.option("cover-url")?.let { log.checked(it, "--cover-url") }
    val imageChecks = images.map { it.second }.checkedIfAsked(args, onNote)
    log.advice.reportStaticImageAdvice(onNote)
    imports.clear()

    return result(
        ImportPreview(
            source = source,
            format = parsed.format.json,
            title = title,
            cards = cards,
            duplicatesCollapsed = draft.duplicatesCollapsed,
            truncated = draft.truncated,
            columnsDropped = if (parsed.droppedColumns) 1 else 0,
            separator = parsed.separator,
            apkg = parsed.apkg,
            imageChecks = imageChecks,
            imageAdvice = log.advice,
        ),
        buildString {
            appendLine("$source would publish $cards ${if (cards == 1) "card" else "cards"}. Nothing was written.")
            if (draft.duplicatesCollapsed > 0) appendLine("Duplicates collapsed: ${draft.duplicatesCollapsed}")
            if (draft.truncated > 0) appendLine("DROPPED at the parser cap: ${draft.truncated}")
            if (parsed.droppedColumns) {
                appendLine("A third column on every row holds text, not image URLs, and would be dropped.")
            }
            parsed.apkg?.let { summary ->
                summary.deckName?.let { appendLine("Anki deck name: $it") }
                appendLine(summary.describe())
            }
            append(parsed.describeSeparator())
        },
    )
}

private class Written(val deck: Deck, val cards: Int)

/** `--check-images` over these URLs, or nothing. See [checkImageUrls]. */
private suspend fun List<String>.checkedIfAsked(args: Args, onNote: (String) -> Unit): List<ImageCheck> =
    if (args.checksImages()) checkImageUrls(this, onNote, args.imageCheckConcurrency()) else emptyList()

/**
 * What a `--resume` run already found on the homeserver.
 *
 * The deck **is** the checkpoint rather than a local cursor file: it records exactly which cards
 * arrived, survives the sandbox being thrown away, and cannot disagree with reality. One deck read
 * buys an import killed by the hourly session expiry (#165) finishing instead of duplicating.
 *
 * Matched on the deck's **title**, which is why `--title` is mandatory and never derived: a title
 * inferred from a filename is one rename away from creating a second deck instead.
 */
private class ResumeState(val deck: Deck?, val alreadyThere: Set<String>)

private suspend fun resumeState(
    args: Args,
    decks: DeckRepository,
    cards: CardRepository,
    title: String,
    onNote: (String) -> Unit,
): ResumeState {
    if (!args.has("resume")) return ResumeState(null, emptySet())
    val owned = decks.listOwned()
    val matches = owned.filter { it.title == title }
    if (matches.isEmpty()) {
        // Not an error: an agent that always passes `--resume` so its retries are safe has to be
        // able to make the *first* run. But not silent either — this is the only thing separating a
        // first run from a `--title` typo about to publish a second copy of a deck the account has.
        onNote(
            "--resume found no deck titled \"$title\", so this is publishing a NEW deck. " +
                "If that was a typo, delete it and re-run. Your decks: " +
                owned.joinToString(", ") { it.title }.ifEmpty { "none" },
        )
        return ResumeState(null, emptySet())
    }
    if (matches.size > 1) {
        // Refused rather than resolved. `firstOrNull` over a listing in homeserver order would pick
        // one arbitrarily and append somebody's cards to the wrong deck — a silent wrong answer,
        // where this is a loud one the user can act on.
        throw CliError(
            ExitCode.BadInput,
            "${matches.size} of your decks are called \"$title\", so --resume cannot tell which " +
                "one to continue: ${matches.joinToString(", ") { it.id }}. Rename or delete one, " +
                "or drop --resume to publish a new deck.",
        )
    }
    val deck = matches.firstOrNull() ?: return ResumeState(null, emptySet())
    val present = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }
    return ResumeState(deck, present.mapTo(mutableSetOf()) { it.identityOf() })
}

/**
 * The kept rows as [Card]s, with any blob-backed picture uploaded on the way through. A TSV's images
 * are URLs and cost nothing to resolve; an `.apkg`'s are bytes that must be written before a card
 * can reference one, because the ref is content-addressed.
 *
 * **The upload happens before the `--resume` filter, not after.** `identityOf` tells two cards
 * asking the same question about different pictures apart by the image's `sha256`, so deciding
 * whether an image card is already published needs the sha, and the sha needs the upload. The cost
 * is that a resumed image-heavy `.apkg` re-writes the archive's blobs: bounded, and quota-neutral
 * because a rewrite lands on the same content-addressed path — but not free in wall-clock.
 */
@Suppress("LongParameterList")
private suspend fun buildCards(
    imports: ImportRepository,
    draft: ImportDraft,
    resume: ResumeState,
    deckId: String,
    media: MediaRepository,
    uploaded: MutableList<MediaRef>,
    onProgress: (String) -> Unit,
): List<Card> {
    val now = System.currentTimeMillis()
    // Keyed on the draft image itself. `DraftCardImage` is a data class over a `ByteArray`, whose
    // equals/hashCode are identity — exactly the question being asked, since `MediaIndex` hands back
    // one instance per distinct blob. A picture on forty cards uploads once.
    val uploads = mutableMapOf<DraftCardImage, MediaRef.Image>()
    return imports.keptRows().mapIndexedNotNull { index, row ->
        val (front, back) = draft.frontBackOf(row)
        Card(
            id = generateId(),
            deckId = deckId,
            updatedAt = now,
            front = CardSide(
                text = front.takeIf { it.isNotBlank() },
                imageRef = resolveImage(
                    imports.rowImage(row.index, isFront = true),
                    deckId, media, uploads, uploaded, onProgress,
                ),
            ),
            back = CardSide(
                text = back.takeIf { it.isNotBlank() },
                imageRef = resolveImage(
                    imports.rowImage(row.index, isFront = false),
                    deckId, media, uploads, uploaded, onProgress,
                ),
            ),
            ord = ordForIndex(index),
        ).takeIf { it.identityOf() !in resume.alreadyThere }
    }
}

/**
 * A draft picture as a [MediaRef.Image]: a URL wrapped, bytes uploaded, nothing left over.
 *
 * **A failed upload throws rather than degrading to `null`.** Swallowing it is how a publish comes
 * back successful with the pictures quietly missing — a failed import (#91) that `--json` reports
 * as fine.
 */
@Suppress("LongParameterList")
private suspend fun resolveImage(
    image: DraftCardImage?,
    deckId: String,
    media: MediaRepository,
    uploads: MutableMap<DraftCardImage, MediaRef.Image>,
    uploaded: MutableList<MediaRef>,
    onProgress: (String) -> Unit,
): MediaRef.Image? {
    val bytes = image?.bytes
    if (image == null) return null
    if (bytes == null) return image.url?.let(::remoteImage)
    uploads[image]?.let { return it }

    onProgress("uploading picture ${uploads.size + 1} (${formatBytes(bytes.size.toLong())})")
    val ref = media.putImage(deckId, bytes, image.mime ?: DEFAULT_IMAGE_MIME)
        .getOrElse { throw asCliError(it) }
    uploads[image] = ref
    uploaded += ref
    return ref
}

private const val DEFAULT_IMAGE_MIME = "image/jpeg"

/**
 * Take back the blobs an aborted publish already wrote.
 *
 * By hand rather than through `DeckRepository.delete`, which walks a manifest — and there is none
 * yet, because media goes up before `publish` writes one. Best-effort and never fatal: the import
 * quite plausibly aborted because storage ran out, and failing the failure replaces a useful error
 * with a useless one.
 */
private suspend fun sweepUploadedMedia(
    media: MediaRepository,
    deckId: String,
    uploaded: List<MediaRef>,
    onNote: (String) -> Unit,
) {
    if (uploaded.isEmpty()) return
    var failed = 0
    uploaded.forEach { ref -> media.delete(deckId, ref).onFailure { failed++ } }
    if (failed > 0) {
        onNote("$failed of ${uploaded.size} pictures this run uploaded could not be removed again.")
    }
}

/**
 * The existing deck with whatever metadata this invocation actually specified applied on top. Null
 * when nothing was, so a bare `--resume` costs no metadata write.
 *
 * Silently dropping `--tag`, `--description` or `--front-lang` on a resumed run was the sharpest of
 * the resume findings: the natural way to use the feature is to re-run the *same command* with
 * `--resume` appended, so an agent lost every flag but `--title` — and a dropped language pair means
 * `Deck.speechReady` stays false and Listen and Speak never appear, with the CLI reporting success.
 *
 * Only what was given is overlaid. Absent is not "set it to the default", which is why the opt-ins
 * go through [Args.flagOrNull]; without that a bare `--resume` would turn off every mode the deck had.
 */
private fun Deck.overlaidWith(args: Args): Deck? {
    val frontLang = args.option("front-lang") ?: frontLang
    val backLang = args.option("back-lang") ?: backLang
    val updated = copy(
        description = args.option("description")?.takeIf { it.isNotBlank() } ?: description,
        coverEmoji = args.option("cover-emoji")?.takeIf { it.isNotBlank() } ?: coverEmoji,
        // Checked at the top of `import`, into the same advice log as every other picture.
        coverImageRef = args.option("cover-url")?.let(::remoteImage)
            ?: coverImageRef,
        tags = args.overlaidTags(this, frontLang, backLang),
        listenEnabled = args.flagOrNull("listen") ?: listenEnabled,
        speakEnabled = args.flagOrNull("speak") ?: speakEnabled,
        typeEnabled = args.flagOrNull("type") ?: typeEnabled,
        reverseEnabled = args.flagOrNull("reverse") ?: reverseEnabled,
        frontLang = frontLang,
        backLang = backLang,
    )
    return updated.takeIf { it != this }
}

/**
 * The resumed deck's tags after this invocation's `--tag` and language flags: `--tag` replaces the
 * set when given, and a **named** pair reconciles the labels it contributes.
 *
 * Named rather than moved, which is `deck edit`'s rule too: restating the pair is how a deck
 * published before the labels existed gains them. When it has not moved the reconciliation is a
 * no-op and `overlaidWith` still returns null.
 *
 * The drop is the half worth spelling out: retyping a deck from Spanish to French has to take
 * `"spanish"` off it, or the deck stays listed as Spanish forever.
 */
private fun Args.overlaidTags(deck: Deck, frontLang: String?, backLang: String?): List<Tag> {
    val requested = options("tag").normalizedTags()
    val tags = requested.ifEmpty { deck.tags.map { it.value } }
    val named = has("front-lang") || has("back-lang")
    val labelled = if (named) {
        LanguageTags.retag(tags, deck.frontLang, deck.backLang, frontLang, backLang)
    } else {
        tags
    }
    return labelled.map(::Tag)
}

@Suppress("LongParameterList")
private fun newDeck(
    args: Args,
    draft: ImportDraft,
    session: Session,
    deckId: String,
    cardCount: Int,
): Deck {
    val now = System.currentTimeMillis()
    val frontLang = args.option("front-lang")
    val backLang = args.option("back-lang")
    return Deck(
        id = deckId,
        authorPubky = session.identity.pubky,
        title = args.requireOption("title").trim(),
        description = args.option("description")?.takeIf { it.isNotBlank() },
        coverEmoji = args.option("cover-emoji")?.takeIf { it.isNotBlank() },
        coverImageRef = args.option("cover-url")?.let(::remoteImage),
        // A declared pair labels the deck, exactly as on a phone. This is the path most decks arrive
        // by (#46), and it was the least discoverable of the three.
        tags = deckTags(args.options("tag"), frontLang, backLang),
        createdAt = now,
        updatedAt = now,
        // publish() writes the chunk table; this is the optimistic count it confirms.
        cardCount = cardCount,
        source = DeckSource(kind = DeckSource.Kind.Import, importedAt = now),
        listenEnabled = args.flag("listen", default = false),
        speakEnabled = args.flag("speak", default = false),
        typeEnabled = args.flag("type", default = false),
        // The one opt-in with a source opinion behind it: an `.apkg` built on a reversed note type
        // says so, and `parseBulkNotes` carries that through. A suggestion, still overridable.
        reverseEnabled = args.flag("reverse", default = draft.suggestsReverse),
        frontLang = frontLang,
        backLang = backLang,
    )
}

/**
 * Write the cards a resumed run is missing, and bring the deck's metadata up to date.
 *
 * One `appendCards` rather than a loop of `upsertCard`, which cost a chunk write *plus* a full
 * manifest read-modify-write per card — 60 writes for 30 cards where a publish spends 2 — making
 * the recovery path slower than the attempt it was recovering, on the same one-hour budget.
 */
private suspend fun appendMissing(
    decks: DeckRepository,
    deckId: String,
    cards: List<Card>,
    metadata: Deck?,
    onProgress: (String) -> Unit,
): Deck {
    decks.getLocal(deckId) ?: decks.sync(deckId).getOrElse { throw asCliError(it) }
    onProgress("writing ${cards.size} missing cards")
    var deck = decks.appendCards(deckId, cards).getOrElse { throw asCliError(it) }
    if (metadata != null) {
        // Metadata last: a failed append should not have renamed the deck it failed to fill.
        onProgress("updating deck metadata")
        // `chunks` and `cardCount` are re-read inside `updateMetadata`'s lock, so the stale ones on
        // this snapshot are discarded — but **`updatedAt` is not**, and it is what `hasUpdate`
        // compares against a follower's last-seen mark. Writing the pre-append value would rewind
        // the manifest below what the append just set, and a follower who had already seen the deck
        // would never be told the new cards exist.
        deck = decks.updateMetadata(metadata.copy(updatedAt = deck.updatedAt))
            .getOrElse { throw asCliError(it) }
    }
    return deck
}
