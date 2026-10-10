package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.DeckView
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.requireUsableOperand
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.generateId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `deck create`, and the three questions it has to answer before it writes anything: what id this
 * deck gets, whether that id is already taken, and what the deck is.
 *
 * Split from `Decks.kt` — the other five verbs are a read or a single call each; this one carries
 * the idempotency contract (`--id` + `--if-not-exists`), the pre-flight (`--dry-run`) and the card
 * file, and they are one story.
 */

/**
 * `deck create`'s own shape, so it can report `--check-images` findings that `deck show` has no
 * way to produce. Same `deck` field either way — a caller reading the deck out of the envelope
 * does not have to know which command wrote it.
 */
@Serializable
data class DeckCreateResult(
    val deck: DeckView,
    @SerialName("image_checks") val imageChecks: List<ImageCheck> = emptyList(),
    /**
     * What is wrong with a picture URL without asking any host — the card file's and the cover's
     * alike. See [ImageAdvice].
     *
     * Reported whether or not `--check-images` was passed, which is why it is not folded into
     * [imageChecks]. It matters most on this command's `--dry-run`, which is documented as the
     * pre-flight for a file you are about to publish with.
     */
    @SerialName("image_advice") val imageAdvice: List<ImageAdvice> = emptyList(),
    /**
     * Whether the deck was published by *this* call — or, under [dryRun], **would be**.
     *
     * The field exists so the two outcomes are distinguishable at all: both are exit 0 carrying
     * the same deck, and an agent retrying an ambiguous failure needs to know whether this call
     * or a previous one put it there.
     *
     * A preview therefore reports `true` for a free id. It reads naturally as "would be created"
     * with [dryRun] beside it, and it is the half that makes the four combinations distinct —
     * reporting `false` for both preview branches left `deck create --id X --if-not-exists
     * --dry-run` unable to answer the one question it is asked, which is whether the id is free.
     */
    val created: Boolean = true,
    /**
     * True when `--dry-run` reported this deck instead of publishing it. See [deckCreate].
     *
     * Read with [created]: `dry_run` says whether anything was written, `created` says what the
     * homeserver holds.
     */
    @SerialName("dry_run") val dryRun: Boolean = false,
    /**
     * Whether the homeserver was asked if the id is free. Only a `--id` is ever asked about: a
     * minted one cannot already exist, which is also why a `--dry-run` without one needs no
     * session (#367).
     */
    @SerialName("id_checked") val idChecked: Boolean = true,
    /** Every `--tag` stored under a different spelling than it was given. See [TagFold]. */
    @SerialName("tags_normalized") val tagsNormalized: List<TagFold> = emptyList(),
)

/**
 * Create a deck, optionally with its cards in the same call.
 *
 * `--title` is required and never derived from a filename. Derivation is not a shortcut worth
 * having: it replaced the hyphen in `Biomas e Sub-ecossistemas Brasileiros` with a space, and that
 * was caught only because a human read the result afterwards (#54, finding "explicit beats
 * inferred").
 *
 * Cards go up in the same `publish` as the manifest, which is what makes this cheap: the
 * repository writes an `incomplete: true` marker manifest first, then the chunks at
 * `MAX_IN_FLIGHT = 4`, then the real manifest — so an interrupted run leaves a deck that is
 * visible and deletable rather than orphaned chunk records under a manifest-less root.
 *
 * A declared `--front-lang`/`--back-lang` pair contributes its labels to the tag set, the way the
 * apps' language pick does — see [deckTags]. A deck that declares no pair gets none.
 *
 * **`--dry-run` reports the deck and stops.** It runs the real path — the `--id` existence check,
 * this command's own card-file reader, every row's validation and `--check-images` — and returns
 * before the publish, with `created` saying whether the deck *would* be published.
 * `import --dry-run` was the only pre-flight there was, and it goes through a *different* parser
 * (#257, item 8), so it could not answer what this command would do with the same file. With
 * `--id` it needs a session, because whether that id is free is a homeserver read. Without one it
 * does not (#367): a minted id has nothing to ask about, so [session] is null, the file is checked
 * offline, and the preview's `author_pubky` is empty.
 */
suspend fun deckCreate(
    args: Args,
    decks: DeckRepository,
    session: Session?,
    onNote: (String) -> Unit = System.err::println,
    onProgress: (String) -> Unit,
): CommandResult {
    val title = args.requireOption("title").trim()
    if (title.isEmpty()) throw CliError(ExitCode.Usage, "--title cannot be empty.")
    args.deckDescription()
    val tagFolds = args.requestedTagFolds()

    val deckId = args.deckIdToCreate()
    // Before the card file is read and before any picture is probed: when the deck is already
    // there this call has nothing left to do, and a `--from-file` of 9,000 rows should not be
    // parsed to find that out.
    existingDeck(args, decks, session, deckId)?.let { existing ->
        return result(
            // `dry_run` still travels, even though this branch writes nothing either way: a
            // caller branching on it must not have to know which of the two reasons applied.
            // `created = false` is what separates this from the preview of a free id.
            DeckCreateResult(existing.toView(), created = false, dryRun = args.has(DRY_RUN_FLAG), idChecked = true),
            "${existing.id} already exists — ${existing.title} (${existing.cardCount} cards). Nothing written.",
        )
    }
    val now = System.currentTimeMillis()
    // Collected rather than printed per row, and emptied out below after `--check-images` — see
    // `ImageAdviceLog`. A 1210-row file of bad thumbnail widths otherwise printed 1210 multi-line
    // notes ahead of the probe's block and put none of them in `--json`.
    val log = ImageAdviceLog()
    val cards = args.option("from-file")
        ?.let { readCardFile(it, log, onNote).requireBothSides().toCards(deckId, now) }
        .orEmpty()
    val imageChecks = if (args.checksImages()) {
        checkImageUrls(
            cards.flatMap { listOfNotNull(it.front.imageRef?.url, it.back.imageRef?.url) },
            onNote,
            args.imageCheckConcurrency(),
        )
    } else {
        emptyList()
    }

    val deck = args.newDeck(deckId, session, title, cards.size, now, log)

    // After the deck is assembled, so `--cover-url`'s advice is in it, and after the probe.
    log.advice.reportStaticImageAdvice(onNote)

    val idChecked = args.option("id") != null
    if (args.has(DRY_RUN_FLAG)) {
        return result(
            DeckCreateResult(
                deck.toView(), imageChecks, log.advice,
                created = true, dryRun = true, idChecked = idChecked, tagsNormalized = tagFolds,
            ),
            "$deckId would be created — $title (${cards.size} cards). Nothing was written." +
                (if (idChecked) "" else " No --id was given, so the homeserver was not asked about one.") +
                tagFolds.describe(),
        )
    }
    requireNotNull(session) { "deck create publishes, so it needs a session" }

    val published = decks.publish(deck, cards) { progress ->
        onProgress("${progress.cardsWritten}/${progress.totalCards} cards, ${progress.chunksWritten}/${progress.totalChunks} chunks")
    }.getOrElse { throw asCliError(it) }

    return result(
        DeckCreateResult(published.toView(), imageChecks, log.advice, idChecked = idChecked, tagsNormalized = tagFolds),
        "Created ${published.id} — ${published.title} (${published.cardCount} cards)" + tagFolds.describe(),
    )
}

/**
 * The deck this invocation describes, before anything is written.
 *
 * `--cover-url` goes through [log] like every other picture rather than straight to stderr, so it
 * lands in the same block and the same `image_advice` array as the card file's.
 */
@Suppress("LongParameterList")
private fun Args.newDeck(
    deckId: String,
    session: Session?,
    title: String,
    cardCount: Int,
    now: Long,
    log: ImageAdviceLog,
): Deck {
    val frontLang = option("front-lang")
    val backLang = option("back-lang")
    return Deck(
        id = deckId,
        authorPubky = session?.identity?.pubky.orEmpty(),
        title = title,
        description = deckDescription(),
        coverEmoji = option("cover-emoji")?.takeIf { it.isNotBlank() },
        coverImageRef = option("cover-url")?.let { log.checked(it, "--cover-url") }?.let(::remoteImage),
        tags = deckTags(options("tag"), frontLang, backLang),
        createdAt = now,
        updatedAt = now,
        cardCount = cardCount,
        source = DeckSource(kind = DeckSource.Kind.Import, importedAt = now),
        listenEnabled = flag("listen", default = false),
        speakEnabled = flag("speak", default = false),
        typeEnabled = flag("type", default = false),
        reverseEnabled = flag("reverse", default = false),
        frontLang = frontLang,
        backLang = backLang,
    )
}

/**
 * The id the new deck gets: `--id` when one was given, otherwise a fresh one.
 *
 * A client-supplied id is what makes a retry addressable (#240, finding 5). Without one, an agent
 * whose `deck create` was killed mid-flight — the hourly expiry and a 2.5 s round trip make that
 * ordinary — can only recover by listing decks and matching on title, which is neither cheap nor
 * race-free, and a second run simply publishes a second deck.
 */
private fun Args.deckIdToCreate(): String {
    val supplied = option("id")?.trim() ?: return generateId().also {
        if (has("if-not-exists")) {
            throw CliError(
                ExitCode.Usage,
                "--if-not-exists needs --id. Without one this command mints a fresh id, which " +
                    "never exists, so the flag could only mean matching on --title — a listing " +
                    "per call, and two decks named the same are indistinguishable anyway.",
            )
        }
    }
    return requireUsableOperand(supplied, "id")
}

/**
 * The deck already at [deckId], or null when there is nothing usable there.
 *
 * Three things this has to get right, none of them visible from the call site.
 *
 * **A read failure that is not a miss is rethrown.** Treating an unreachable homeserver as "the
 * deck does not exist" is how `--if-not-exists` would publish the duplicate it exists to prevent.
 *
 * **It reads the manifest, not the deck.** `DeckRepository.sync` is `fetchRemote` *plus*
 * `fetchByDeck`, which on a cold CLI process pulls every chunk — ~200 reads for a 20k-card deck,
 * paid on every idempotent retry, to answer a question the manifest alone settles. `fetchRemote`
 * reads one record and carries everything used here: the author, `incomplete`, the title and the
 * count.
 *
 * **A deck belonging to someone else is not an answer to "is my id taken?".** Reading
 * `session.identity.pubky`'s own namespace is what guarantees that — `sync` resolves an unknown
 * id's author through the caller's *follow* records first, so a `--id` colliding with a followed
 * deck used to come back as that author's manifest: `--if-not-exists` accepting a deck you cannot
 * write, or a refusal for an id that was free all along. The author check below is then a cheap
 * consistency assertion on a record this client does not control the contents of, rather than the
 * guarantee itself.
 *
 * **An `incomplete` manifest is not a deck.** `publish` writes that marker *before* the chunks, so
 * a killed `deck create --id X` — the case `--if-not-exists` exists for — leaves one behind.
 * Accepting it would report success for a deck whose cards were never uploaded, permanently.
 *
 * Without `--if-not-exists` an existing id is refused rather than overwritten: `publish` replaces
 * the manifest and its whole chunk table, so a reused id would take a deck's cards with it — and
 * nothing here prompts, so this is the only place that can say no.
 */
private suspend fun existingDeck(
    args: Args,
    decks: DeckRepository,
    session: Session?,
    deckId: String,
): Deck? {
    if (args.option("id") == null) return null
    requireNotNull(session) { "deck create --id needs a session to check the id" }
    val existing = decks.fetchRemote(session.identity.pubky, deckId).fold(
        onSuccess = { it },
        onFailure = { if (ExitCode.of(it) == ExitCode.NotFound) null else throw asCliError(it) },
    ) ?: return null
    if (existing.authorPubky != session.identity.pubky) return null
    if (existing.incomplete) {
        if (args.has("if-not-exists")) return null
        throw CliError(
            ExitCode.BadInput,
            "Deck $deckId exists but is incomplete — a previous publish did not finish, so its " +
                "cards are missing. Pass --if-not-exists to finish it by re-publishing, or use " +
                "`deck delete`.",
        )
    }
    if (args.has("if-not-exists")) return existing
    throw CliError(
        ExitCode.BadInput,
        "Deck $deckId already exists — \"${existing.title}\", ${existing.cardCount} cards. " +
            "Publishing over it would replace its chunk table and lose them. Pass " +
            "--if-not-exists to accept it as it is, or use `deck edit` / `deck delete`.",
    )
}
