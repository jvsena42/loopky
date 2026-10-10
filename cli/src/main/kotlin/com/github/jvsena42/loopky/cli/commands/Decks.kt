package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.DeckView
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toLine
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.DeckLimits
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.remoteImageRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeckListResult(val decks: List<DeckView>, val count: Int)

@Serializable
data class DeckShowResult(val deck: DeckView)

@Serializable
data class DeckDeleteResult(val id: String, val deleted: Boolean)

@Serializable
data class DeckCompactResult(
    /** The deck as it stands afterwards, chunk table included — see `DeckView.chunks`. */
    val deck: DeckView,
    val id: String,
    val merges: Int,
    @SerialName("cards_moved") val cardsMoved: Int,
    @SerialName("chunks_before") val chunksBefore: Int,
    @SerialName("chunks_after") val chunksAfter: Int,
    val complete: Boolean,
)

suspend fun deckList(decks: DeckRepository): CommandResult {
    val owned = decks.listOwned().map { it.toView() }
    return result(
        DeckListResult(owned, owned.size),
        if (owned.isEmpty()) "No decks." else owned.joinToString("\n") { it.toLine() },
    )
}

/**
 * A deck as it is on the homeserver, not as this process happens to remember it.
 *
 * [DeckRepository.sync] rather than `getLocal`, because a fresh invocation has an empty cache and
 * `--json` is a verification channel: a caller diffing what it asked for against what is stored
 * has to be reading the homeserver.
 */
suspend fun deckShow(args: Args, decks: DeckRepository): CommandResult {
    val id = args.requireWord(2, "deckId")
    val deck = decks.sync(id).getOrElse { throw asCliError(it) }
    return result(DeckShowResult(deck.toView()), deck.toView().describe())
}

private fun DeckView.describe(): String = buildString {
    appendLine("Id:          $id")
    appendLine("Title:       $title")
    description?.let { appendLine("Description: $it") }
    appendLine("Cards:       $cardCount")
    appendLine("Tags:        ${tags.joinToString(", ").ifEmpty { "—" }}")
    coverImage?.let { appendLine("Cover:       ${it.url ?: it.sha256}") }
    if (frontLang != null || backLang != null) {
        appendLine("Languages:   ${frontLang ?: "?"} -> ${backLang ?: "?"}")
    }
    appendLine(
        "Study modes: " + listOfNotNull(
            "listen".takeIf { listenEnabled },
            "speak".takeIf { speakEnabled },
            "type".takeIf { typeEnabled },
            "reverse".takeIf { reverseEnabled },
        ).joinToString(", ").ifEmpty { "flip only" },
    )
    if (incomplete) appendLine("INCOMPLETE:  a publish did not finish; re-publish or delete it.")
    append("Uri:         $uri")
}

suspend fun deckDelete(args: Args, decks: DeckRepository): CommandResult {
    val id = args.requireWord(2, "deckId")
    decks.delete(id).getOrElse { throw asCliError(it) }
    return result(DeckDeleteResult(id, deleted = true), "Deleted $id")
}

suspend fun deckSync(args: Args, decks: DeckRepository, cards: CardRepository): CommandResult {
    val id = args.requireWord(2, "deckId")
    val deck = decks.sync(id).getOrElse { throw asCliError(it) }
    // Pull the cards too: `sync` refreshes the manifest, and a caller asking to sync a deck means
    // the deck, not its metadata. Chunks whose `updated_at` has not moved are not re-fetched.
    cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }
    return result(DeckShowResult(deck.toView()), "Synced ${deck.id} — ${deck.cardCount} cards")
}

/**
 * Fold away the holes card deletes leave behind.
 *
 * Explicit rather than automatic, and that is the deliberate half of `InlineBackgroundTasks`
 * dropping the scheduled pass: a process that ends when its command does has no deferred window,
 * and a surprise compaction in the middle of an import is exactly what the Android scheduler's
 * constraints exist to prevent. Nothing here is required for correctness — a deck with holes is a
 * deck every reader already handles — so it is offered, never imposed.
 */
suspend fun deckCompact(args: Args, decks: DeckRepository): CommandResult {
    val id = args.requireWord(2, "deckId")
    val outcome = decks.compactDeck(id).getOrElse { throw asCliError(it) }
    // Re-read afterwards rather than reporting only the outcome's counts: compaction's whole job
    // is rearranging the chunk table, and a caller has to be able to check the work rather than
    // take the command's word for it.
    val compacted = decks.sync(id).getOrElse { throw asCliError(it) }
    return result(
        DeckCompactResult(
            deck = compacted.toView(),
            id = id,
            merges = outcome.merges,
            cardsMoved = outcome.cardsMoved,
            chunksBefore = outcome.chunksBefore,
            chunksAfter = outcome.chunksAfter,
            complete = outcome.complete,
        ),
        "Compacted $id: ${outcome.merges} merges, ${outcome.cardsMoved} cards moved, " +
            "${outcome.chunksBefore} -> ${outcome.chunksAfter} chunks" +
            if (outcome.complete) "" else " (budget reached; run again to continue)",
    )
}

/**
 * A remote image reference — a URL, with no blob and no upload.
 *
 * Delegates to the shared factory rather than rebuilding the shape, because a ref written a second
 * way is a ref one of the clients cannot render — and a validation added to one copy is a
 * validation the other copies do not have. The URL is already checked by the time it arrives (see
 * `requireRenderableImageUrl`), so a rejection here is a bug rather than bad input.
 */
internal fun remoteImage(url: String): MediaRef.Image = requireNotNull(remoteImageRef(url)) {
    "not a renderable image URL: $url"
}

/**
 * `--description` as it will be stored, or null when absent or blank. Past
 * [DeckLimits.DESCRIPTION_MAX_LENGTH] it is refused rather than cut: the apps' editors hold the same
 * cap, and a truncated value would drop the end of a picture credit with exit 0.
 */
internal fun Args.deckDescription(): String? {
    val description = option("description")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (description.length > DeckLimits.DESCRIPTION_MAX_LENGTH) {
        throw CliError(
            ExitCode.BadInput,
            "--description is ${description.length} characters; a deck description holds at most " +
                "${DeckLimits.DESCRIPTION_MAX_LENGTH}.",
        )
    }
    return description
}

/**
 * `--dry-run`: read, validate, report, write nothing.
 *
 * On `import`, `deck create` and `card add` — the three commands that take a file of cards. Each
 * runs its **own** path up to the write rather than borrowing another's, which is the whole point:
 * a pre-flight through a different parser answers a question nobody asked (#257, item 8).
 */
internal const val DRY_RUN_FLAG = "dry-run"

/** A `--name`/`--no-name` pair, since a deck opt-in has to be turnable *off* as well as on. */
internal fun Args.flag(name: String, default: Boolean): Boolean = flagOrNull(name) ?: default

internal fun List<CardFileRow>.toCards(deckId: String, now: Long): List<Card> =
    mapIndexed { index, row -> row.toCard(deckId, now, index) }
