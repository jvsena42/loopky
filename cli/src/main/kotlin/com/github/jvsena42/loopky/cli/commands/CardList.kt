package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CardView
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toLine
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CardListResult(
    @SerialName("deck_id") val deckId: String,
    val cards: List<CardView>,
    /** Cards in *this* answer. [cardCount] is what the deck holds. */
    val count: Int,
    @SerialName("card_count") val cardCount: Int = 0,
    /**
     * Where to carry on from, or null when this answer reached the end of the deck.
     *
     * Opaque — a chunk number and an offset inside it — and only ever produced by this command.
     * A deck edited between two pages can shift what a cursor lands on; the cursor is a place in
     * the deck, not a snapshot of it, which is the same guarantee the chunk table itself gives.
     */
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class CardWriteResult(
    @SerialName("deck_id") val deckId: String,
    val cards: List<CardView>,
    val written: Int,
    /**
     * Rows that matched a card already in the deck and were therefore not written again.
     *
     * `card add` twice with the same front and back has to be *detectable*, or an agent retrying
     * after a session expiry double-posts every card it had already written (#54). Reported rather
     * than merely skipped, so a caller can tell "already there" from "did nothing".
     */
    val skipped: Int = 0,
    /**
     * Cards this call actually removed — 0 when the id was not in the deck.
     *
     * `card rm` used to answer identically whether it deleted a card or did nothing: `deleteCard`
     * treats a missing card as a no-op and the CLI reported that as success, so an agent pruning ids
     * could not tell which were real without re-reading the deck between every delete.
     *
     * Reported rather than turned into an error, and the asymmetry with `card edit` — which does
     * return `not_found` — is deliberate: removing a card that is already gone leaves the deck in the
     * state the caller asked for, so failing it would break the retry-after-expiry pattern the whole
     * surface is built around. An *edit* has no such reading.
     */
    val removed: Int = 0,
    @SerialName("card_count") val cardCount: Int = 0,
    /**
     * Rows the homeserver refused, with the id and the reason for each.
     *
     * A batch used to stop at the first failure and report only a message, so 35 of 665 rows had
     * landed with no way to tell which — and no `--resume` to pick it back up (#229, item 2). This
     * travels on the *failure* envelope as well as the success one, which is the point: it is the
     * only thing that tells a caller where the write stopped.
     */
    val failed: Int = 0,
    val failures: List<CardWriteFailure> = emptyList(),
    /** Rows the batch never reached, because it stopped at a failure nothing could recover from. */
    @SerialName("not_attempted") val notAttempted: Int = 0,
    /**
     * What `--check-images` found, and only what is worth reporting: a URL that answered 2xx with
     * an image type produces no row. Empty when the flag was not passed.
     */
    @SerialName("image_checks") val imageChecks: List<ImageCheck> = emptyList(),
    /**
     * What is wrong with a picture URL without asking any host. See [ImageAdvice].
     *
     * Reported whether or not `--check-images` was passed, which is why it is not folded into
     * [imageChecks] — and why the two are separate arrays rather than one with a discriminator.
     */
    @SerialName("image_advice") val imageAdvice: List<ImageAdvice> = emptyList(),
    /**
     * True when `--dry-run` reported what would be written instead of writing it.
     *
     * [written] then counts rows that *would* be written, and [cards] is empty — nothing has an id
     * from the homeserver yet. [skipped] is real: the dedupe read the deck.
     */
    @SerialName("dry_run") val dryRun: Boolean = false,
)

/** One row the homeserver refused. [row] is 1-based, matching the file the caller handed in. */
@Serializable
data class CardWriteFailure(
    val row: Int,
    @SerialName("card_id") val cardId: String? = null,
    /** The [ExitCode] this row's failure classifies as, by name — `server_error`, `not_found`. */
    val code: String,
    val message: String,
)

/**
 * The deck's cards, all of them or a page.
 *
 * **Plain `card list` still reads the whole deck**, because that is what it means. `--limit` and
 * `--cursor` are what make a large one affordable to iterate on: they walk the manifest's chunk
 * table and fetch only the records a page needs, so deciding which of 4,000 cards still want a
 * picture no longer costs 700 KB per pass (#229, item 6). `--missing-image` / `--has-image` narrow
 * what comes back and compose with both.
 *
 * A page is in the deck's own chunk order, which is study order everywhere it differs from
 * nothing: chunk `n` owns a private slice of the ord line (§8.4), so cards cannot sort across
 * chunks. Sorting *within* a page is still by `ord`.
 *
 * There is no server-side filter to ask for instead. The homeserver stores opaque records and
 * Nexus indexes tags, not cards, so `--missing-image` is applied here — what it saves is the
 * output and the caller's work, and with `--limit` it saves the fetch too.
 */
suspend fun cardList(
    args: Args,
    decks: DeckRepository,
    cards: CardRepository,
    onNote: (String) -> Unit = System.err::println,
): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    val deck = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val filter = CardFilter.of(args)
    val limit = if (args.has("limit")) args.positiveInt("limit", 0) else null
    val cursor = args.option("cursor")?.let(::parseCursor)

    val page = if (limit == null && cursor == null) {
        val all = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }
            .inStudyOrder()
            .filter(filter::matches)
        Page(all, null)
    } else {
        readPage(deck, cards, filter, limit, cursor)
    }

    page.nextCursor?.let {
        // stderr, not stdout: one line per card is what `card list` prints, and a trailer would
        // make the line count disagree with the card count for anything counting them.
        onNote("loopky: more cards — carry on with --cursor $it")
    }
    return result(
        CardListResult(
            deckId = deckId,
            cards = page.cards.map { it.toView() },
            count = page.cards.size,
            cardCount = deck.cardCount,
            nextCursor = page.nextCursor,
        ),
        if (page.cards.isEmpty()) "No cards." else page.cards.joinToString("\n") { it.toView().toLine() },
    )
}

private class Page(val cards: List<Card>, val nextCursor: String?)

/**
 * Walk the chunk table from [cursor], reading only the records the page needs.
 *
 * The table is **not contiguous** — compaction folds a pair of neighbours and drops the higher `n`
 * — so this reads `chunks[].n` rather than counting. A cursor naming a chunk that has since been
 * folded away resumes at the next one that still exists rather than failing: the caller asked to
 * carry on, and the alternative is an error it can do nothing about.
 */
private suspend fun readPage(
    deck: Deck,
    cards: CardRepository,
    filter: CardFilter,
    limit: Int?,
    cursor: Cursor?,
): Page {
    val chunks = deck.chunks.map { it.n }.sorted().filter { cursor == null || it >= cursor.chunk }
    val collected = mutableListOf<Card>()
    for (n in chunks) {
        val offset = if (n == cursor?.chunk) cursor.offset else 0
        val chunk = cards.readChunk(deck, n).getOrElse { throw asCliError(it) }.inStudyOrder()
        for ((position, card) in chunk.withIndex()) {
            if (position < offset || !filter.matches(card)) continue
            // The cursor is minted from the card that did *not* fit, so it is served next time
            // rather than skipped — which is why the limit is checked before the card is taken.
            if (limit != null && collected.size >= limit) return Page(collected, "$n:$position")
            collected += card
        }
    }
    return Page(collected, null)
}

private class Cursor(val chunk: Int, val offset: Int)

private fun parseCursor(raw: String): Cursor {
    val parts = raw.split(':').map { it.toIntOrNull() }
    val chunk = parts.getOrNull(0)?.takeIf { it >= 0 }
    val offset = parts.getOrNull(1)?.takeIf { it >= 0 }
    if (parts.size != CURSOR_PARTS || chunk == null || offset == null) {
        throw CliError(
            ExitCode.Usage,
            "--cursor takes a value this command produced, not '$raw'. It is in `card list --json`'s " +
                "next_cursor when there is more to read.",
        )
    }
    return Cursor(chunk, offset)
}

private const val CURSOR_PARTS = 2

/** Which cards a listing keeps. Applied here because nothing upstream can be asked — see [cardList]. */
private enum class CardFilter {
    All,
    MissingImage,
    HasImage,
    ;

    fun matches(card: Card): Boolean {
        val hasImage = card.front.imageRef != null || card.back.imageRef != null
        return when (this) {
            All -> true
            MissingImage -> !hasImage
            HasImage -> hasImage
        }
    }

    companion object {
        fun of(args: Args): CardFilter = when {
            args.has("missing-image") && args.has("has-image") -> throw CliError(
                ExitCode.Usage,
                "--missing-image and --has-image ask for opposite things; pass one or neither.",
            )

            args.has("missing-image") -> MissingImage
            args.has("has-image") -> HasImage
            else -> All
        }
    }
}
