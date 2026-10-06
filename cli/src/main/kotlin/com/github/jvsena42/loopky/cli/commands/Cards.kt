package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.inStudyOrder

/**
 * Add one card, or a fileful.
 *
 * **Idempotent by front/back.** A row whose two sides already exist is skipped and counted, never
 * written twice. The session dies after about an hour and nothing renews it (#165), so an agent's
 * normal recovery is to re-run the command — and a surface where re-running duplicates the work is
 * one where a session expiry costs the deck rather than the retry.
 *
 * **A batch is appended in groups, not card by card** (#257, item 2). One `upsertCard` per card is
 * a chunk write *plus* a whole-manifest read-modify-write each: 170 cards took ten minutes and
 * emitted nothing until the end, where `deck create` writes 1210 in seconds. `appendCards` writes
 * a group's chunks and patches the manifest once, so a group of [APPEND_GROUP] costs two writes
 * rather than two hundred.
 *
 * Groups rather than one call for the whole file, because a group is what survives a failure. An
 * append is all-or-nothing — the manifest patch lands last — so a batch that dies partway leaves
 * every completed group on the homeserver and re-running skips them, which is the same recovery
 * the per-card path gave and the reason it was worth keeping.
 *
 * **The dedupe costs a full card read** — ~200 chunk requests on a 20k-card deck before the first
 * write. There is no index to ask instead, and skipping it would trade the retry guarantee for speed.
 * That is the other reason to use `--from-file`: the read is paid once for the whole batch.
 *
 * **`--dry-run` stops after the planning.** Every row is read, validated and deduped against the
 * deck, `--check-images` runs, and nothing is written — so the answer comes from the code path
 * that would actually run, which `import --dry-run` could never give for this command (#257,
 * item 8). It needs a session: the dedupe is a read of the deck.
 */
suspend fun cardAdd(
    args: Args,
    decks: DeckRepository,
    cards: CardRepository,
    onNote: (String) -> Unit = System.err::println,
    onProgress: (String) -> Unit = {},
): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    val deck = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val existing = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }

    // Collected as the file is read and emptied out after `--check-images`, so the advice reaches
    // both stderr and `--json` — see `ImageAdviceLog`.
    val log = ImageAdviceLog()
    args.refuseCardFlagsBesideFile()
    val rows = (
        args.option("from-file")?.let { readCardFile(it, log, onNote) } ?: listOf(
            CardFileRow(
                front = args.option("front"),
                back = args.option("back"),
                frontImageUrl = args.option("front-image")?.let { log.checked(it, "--front-image") },
                backImageUrl = args.option("back-image")?.let { log.checked(it, "--back-image") },
            ).also {
                if (it.isEmpty) throw CliError(ExitCode.Usage, "Give --front/--back, or --from-file.")
            },
        )
        ).requireBothSides()

    val seen = existing.mapTo(mutableSetOf()) { it.identityOf() }
    val now = System.currentTimeMillis()
    val planned = mutableListOf<PlannedWrite>()
    var skipped = 0

    for ((index, row) in rows.withIndex()) {
        // No ord is computed here on purpose. `appendCards` assigns one from the chunk the card
        // lands in and ignores whatever the caller sent, so an ord invented here would be dead
        // weight that *looks* meaningful — which is precisely how this command came to report a
        // number the homeserver never stored.
        val card = row.toCard(deckId, now, index = 0)
        if (!seen.add(card.identityOf())) {
            skipped++
            continue
        }
        planned += PlannedWrite(row = index + 1, card = card)
    }

    val checks = planned.checkedImages(args, onNote)
    log.advice.reportStaticImageAdvice(onNote)
    if (args.has(DRY_RUN_FLAG)) {
        return result(
            CardWriteResult(
                deckId = deckId,
                cards = emptyList(),
                written = planned.size,
                skipped = skipped,
                cardCount = deck.cardCount,
                imageChecks = checks,
                imageAdvice = log.advice,
                dryRun = true,
            ),
            "Would add ${planned.size} card(s) to $deckId" +
                (if (skipped > 0) ", skipping $skipped already present" else "") +
                ". Nothing was written.",
        )
    }
    return appendBatch(deckId, deck, decks, cards, planned, skipped, checks, log.advice, onProgress)
}

/**
 * Change cards that already exist, one or a fileful. A field that is not given is left alone rather
 * than cleared; clearing one needs an explicit empty value (`--back=`, `--clear-back-image`, a JSONL
 * `null`), because a batch file that omitted a column would otherwise silently wipe it on every row
 * it touched.
 *
 * **Idempotent, which is what a `--resume` would have been.** A row whose fields already hold what
 * it asks for is skipped rather than rewritten, so re-running the same file after a failure applies
 * only what is missing — no cursor to keep, nothing to pass, and no `updated_at` churn on rows that
 * did not change. `card edit --from-file` stopping at the first failure with 35 of 665 rows applied
 * and no way to resume was the most expensive finding in #229.
 *
 * **Everything is checked before anything is written.** Ids, both-sides, image URLs: the whole file
 * is resolved into cards first, so a bad row 400 fails the command with the homeserver untouched
 * rather than 399 rows in.
 */
suspend fun cardEdit(
    args: Args,
    decks: DeckRepository,
    cards: CardRepository,
    onNote: (String) -> Unit = System.err::println,
): CommandResult {
    // Operands and the file are resolved before the first read, so a usage mistake exits 9 rather
    // than surfacing as whatever the homeserver says about the deck (#240, #370).
    val deckId = args.requireWord(2, "deckId")
    val log = ImageAdviceLog()
    args.refuseCardFlagsBesideFile()
    val rows = args.option("from-file")?.let { readCardFile(it, log, onNote) } ?: listOf(
        CardFileRow(
            id = args.requireWord(3, "cardId"),
            front = args.option("front"),
            back = args.option("back"),
            frontImageUrl = args.editedImage("front", log),
            backImageUrl = args.editedImage("back", log),
        ),
    )
    rows.forEach { row ->
        if (row.id == null) {
            throw CliError(
                ExitCode.Usage,
                "Every row of a card edit --from-file needs an id. Read them with `card list --json`.",
            )
        }
    }

    val deck = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val existing = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }.associateBy { it.id }

    val now = System.currentTimeMillis()
    val planned = mutableListOf<PlannedWrite>()
    var skipped = 0
    var cleared = 0
    for ((index, row) in rows.withIndex()) {
        val id = requireNotNull(row.id)
        val current = existing[id]
            ?: throw CliError(ExitCode.NotFound, "Deck $deckId has no card $id.")
        val updated = current.applying(row, now).requireBothSides(id)
        // `updatedAt` is stamped on every row, so comparing the whole card would never match.
        if (updated.sameContentAs(current)) {
            skipped++
            continue
        }
        cleared += current.fieldsLostIn(updated)
        planned += PlannedWrite(row = index + 1, card = updated)
    }
    // An explicit null clears, and a serializer that writes every unset optional as null says
    // "clear" without meaning it. Said before the write so the count is seen even if it is not.
    if (cleared > 0) onNote("loopky: this edit removes $cleared text side(s) or picture(s) that the cards have now.")

    val checks = planned.checkedImages(args, onNote)
    log.advice.reportStaticImageAdvice(onNote)
    return applyBatch(deckId, deck, decks, cards, planned, skipped, BatchVerb.Edit, checks, log.advice)
}

/**
 * One side's picture as a single-card edit asked for it: null leaves it alone, blank removes it.
 *
 * `--clear-<side>-image` and an empty `--<side>-image=` are the same request. Both exist because
 * the empty value is what `--back=` already means for text, and the switch is what a caller
 * reaches for after `deck edit --clear-cover` (#453).
 */
private fun Args.editedImage(side: String, log: ImageAdviceLog): String? {
    val flag = "$side-image"
    val url = option(flag)
    if (has("clear-$flag")) {
        if (!url.isNullOrBlank()) {
            throw CliError(ExitCode.Usage, "--clear-$flag and --$flag say opposite things; pass one of them.")
        }
        return ""
    }
    return url?.let { if (it.isBlank()) "" else log.checked(it, "--$flag") }
}

/**
 * Refuse a single-card flag beside `--from-file`: the file wins, so the flag was accepted and then
 * ignored — a card the caller believes they set and did not.
 */
private fun Args.refuseCardFlagsBesideFile() {
    if (!has("from-file")) return
    val flag = SINGLE_CARD_FLAGS.firstOrNull(::has) ?: return
    val inFile = if (flag.startsWith("clear-")) {
        " In a card file, clear a picture with an explicit null: " +
            "{\"id\":\"…\",\"${flag.removePrefix("clear-").replace('-', '_')}_url\":null}"
    } else {
        ""
    }
    throw CliError(
        ExitCode.Usage,
        "--$flag is for the one card named on the command line, and --from-file takes its cards " +
            "from the file; pass one or the other.$inFile",
    )
}

private val SINGLE_CARD_FLAGS =
    listOf("front", "back", "front-image", "back-image", "clear-front-image", "clear-back-image")

/**
 * `--check-images` over the pictures this batch is about to write, or nothing.
 *
 * The rows the batch **skipped** are deliberately not probed: their pictures are already on the
 * homeserver and were not asked about, so checking them would turn a two-row edit of a 4,000-card
 * deck into 4,000 requests.
 */
private suspend fun List<PlannedWrite>.checkedImages(args: Args, onNote: (String) -> Unit): List<ImageCheck> {
    if (!args.checksImages()) return emptyList()
    val urls = flatMap { listOfNotNull(it.card.front.imageRef?.url, it.card.back.imageRef?.url) }
    return checkImageUrls(urls, onNote, args.imageCheckConcurrency())
}

suspend fun cardRemove(args: Args, decks: DeckRepository): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    val cardId = args.requireWord(3, "cardId")
    // The manifest read that makes the answer possible. `deleteCard` returns the deck either way,
    // so without a count from before there is nothing to compare against — and the chunk table is
    // where `cardCount` comes from, so this is one record, not the deck's cards.
    val before = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val after = decks.deleteCard(deckId, cardId).getOrElse { throw asCliError(it) }
    val removed = (before.cardCount - after.cardCount).coerceAtLeast(0)
    return result(
        CardWriteResult(
            deckId = deckId,
            cards = emptyList(),
            written = 0,
            removed = removed,
            cardCount = after.cardCount,
        ),
        if (removed > 0) {
            "Removed $cardId from $deckId (${after.cardCount} cards left)"
        } else {
            "Deck $deckId has no card $cardId — nothing removed (${after.cardCount} cards)."
        },
    )
}

/**
 * Move one card to another place in the study order.
 *
 * The same write the app's deck editor makes ([DeckRepository.moveCard]), so it costs one chunk
 * record and the manifest, two when the card crosses a chunk boundary. Review state is keyed by
 * card id and never by position, so a reader who has studied the card keeps their history.
 *
 * **The write is one chunk; the read is the whole deck.** A position means nothing without every
 * card before it, so a standalone move in a fresh process fetches every chunk record. Inside
 * `batch` only the first move pays that, which is the reason several moves belong in one.
 *
 * **Idempotent.** A card already where it was asked to go is reported as `moved: false` and
 * nothing is written, so a batch of moves can be re-run after a session expiry.
 */
suspend fun cardMove(args: Args, decks: DeckRepository, cards: CardRepository): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    val cardId = args.requireWord(3, "cardId")
    val to = args.positiveIntOrNull(MOVE_TO)
    val after = args.option(MOVE_AFTER)
    if ((to == null) == (after == null)) {
        throw CliError(ExitCode.Usage, "Pass exactly one of --$MOVE_TO <position> or --$MOVE_AFTER <cardId>.")
    }
    if (after == cardId) throw CliError(ExitCode.BadInput, "A card cannot be moved after itself.")

    val deck = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val ordered = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }.inStudyOrder()
    val from = ordered.indexOfFirst { it.id == cardId }
    if (from < 0) throw CliError(ExitCode.NotFound, "Deck $deckId has no card $cardId.")

    // `moveCard` reads its index against the deck without the moved card, so the target is
    // computed over the same list.
    val others = ordered.filterNot { it.id == cardId }
    val target = if (after != null) {
        val anchor = others.indexOfFirst { it.id == after }
        if (anchor < 0) throw CliError(ExitCode.NotFound, "Deck $deckId has no card $after to move after.")
        anchor + 1
    } else {
        (requireNotNull(to) - 1).coerceAtMost(others.size)
    }

    val moved = target != from
    val written = if (moved) decks.moveCard(deckId, cardId, target).getOrElse { throw asCliError(it) } else deck
    // Read back rather than echoed: `--json` reports the `ord` that was stored, and the card as it
    // was before the move carries the old one.
    val card = if (moved) {
        cards.get(deckId, cardId) ?: throw CliError(
            ExitCode.Internal,
            "Moved $cardId to position ${target + 1}, but it could not be read back. " +
                "Check it with `loopky card list $deckId`.",
        )
    } else {
        ordered[from]
    }
    return result(
        CardMoveResult(
            deckId = deckId,
            card = card.toView(),
            position = target + 1,
            moved = moved,
            cardCount = written.cardCount,
        ),
        if (moved) {
            "Moved $cardId to position ${target + 1} of ${written.cardCount} in $deckId"
        } else {
            "$cardId is already at position ${target + 1} of ${written.cardCount} — nothing written."
        },
    )
}

private const val MOVE_TO = "to"
private const val MOVE_AFTER = "after"

/**
 * Refuse a card an edit has emptied one side of.
 *
 * `--back=` is the documented way to clear a side, so this is the supported gesture rather than
 * misuse — and `upsertCard` `require`s both sides, throwing an `IllegalArgumentException` that
 * `toErrorReason` classifies `Unknown`, so the user got exit 1 "internal" plus a Kotlin assertion
 * string for a blank column in their own file. An agent told "internal" retries; told exit 9 it fixes
 * its input.
 *
 * The row-level guard cannot cover this: an edit row is *allowed* to be partial, so what has to be
 * checked is the card the edit produces.
 */
private fun Card.requireBothSides(id: String): Card = also {
    if (front.isEmpty || back.isEmpty) {
        throw CliError(
            ExitCode.BadInput,
            "Card $id would be left with an empty ${if (front.isEmpty) "front" else "back"}; " +
                "a card needs both sides. An image counts as a side.",
        )
    }
}

/**
 * Whether an edit would leave the card exactly as it already is.
 *
 * `updatedAt` is stamped on every row before this is asked, so comparing whole cards would never
 * match — and `ord` belongs to the chunk the card lives in rather than to the edit. What is left is
 * what a card file can express, which is what makes re-running the same file cheap instead of a
 * rewrite of every row (#229, item 2).
 */
private fun Card.sameContentAs(other: Card): Boolean =
    front == other.front && back == other.back

private fun Card.fieldsLostIn(updated: Card): Int = listOf(
    front.text to updated.front.text,
    back.text to updated.back.text,
    front.imageRef to updated.front.imageRef,
    back.imageRef to updated.back.imageRef,
).count { (before, after) -> before != null && after == null }

private fun Card.applying(row: CardFileRow, now: Long): Card = copy(
    updatedAt = now,
    front = front.applying(row.front, row.frontImageUrl),
    back = back.applying(row.back, row.backImageUrl),
)

private fun CardSide.applying(text: String?, imageUrl: String?): CardSide = CardSide(
    text = if (text == null) this.text else text.takeIf { it.isNotBlank() },
    imageRef = when {
        imageUrl == null -> imageRef
        imageUrl.isBlank() -> null
        else -> remoteImage(imageUrl)
    },
    audioRef = audioRef,
)

/**
 * What makes two cards "the same card" for a repeated `card add` or an `import --resume`.
 *
 * Text and image together, normalised for whitespace and case. Text alone would collapse two cards
 * asking the same question about different pictures — a flags deck is exactly that — and including
 * the ord or the id would defeat the check, since both are freshly minted.
 *
 * One definition, shared with `import`: a `--resume` that dedupes differently from the `card add`
 * before it writes the duplicates the mechanism exists to prevent.
 */
internal fun Card.identityOf(): String = listOf(
    front.text.orEmpty().trim().lowercase(),
    back.text.orEmpty().trim().lowercase(),
    front.imageRef?.let { it.url ?: it.sha256 }.orEmpty(),
    back.imageRef?.let { it.url ?: it.sha256 }.orEmpty(),
).joinToString(IDENTITY_SEPARATOR)

/**
 * The separator between a card's four identity fields: `NUL`, because no card text can contain it,
 * so `"ab" + "c"` and `"a" + "bc"` cannot collide.
 *
 * **Written as an escape, and that is the point of the constant.** It used to be a literal NUL *byte*
 * in the source, which made this file binary as far as `grep` is concerned — `grep -rn
 * CardWriteResult` found nothing at all, silently, and exited 1.
 */
private const val IDENTITY_SEPARATOR = "\u0000"
