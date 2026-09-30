package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide

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
 * than cleared; clearing a side needs an explicit empty value (`--back=`), because a batch file that
 * omitted a column would otherwise silently wipe it on every row it touched.
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
    val rows = args.option("from-file")?.let { readCardFile(it, log, onNote) } ?: listOf(
        CardFileRow(
            id = args.requireWord(3, "cardId"),
            front = args.option("front"),
            back = args.option("back"),
            frontImageUrl = args.option("front-image")?.let { log.checked(it, "--front-image") },
            backImageUrl = args.option("back-image")?.let { log.checked(it, "--back-image") },
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
        planned += PlannedWrite(row = index + 1, card = updated)
    }

    val checks = planned.checkedImages(args, onNote)
    log.advice.reportStaticImageAdvice(onNote)
    return applyBatch(deckId, deck, decks, cards, planned, skipped, BatchVerb.Edit, checks, log.advice)
}

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
