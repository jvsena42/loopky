package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.ChunkView
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Put a whole deck into the order a file gives (#449).
 *
 * `card mv` is one card a command, which is one or two writes each and leaves the landing record
 * bigger for good; a second round of cards sorted in with their episodes is thousands of them.
 * This hands [DeckRepository.reorderCards] the whole order instead: two writes a changed record,
 * records back at their normal size, and every card id kept, so nobody's review state moves.
 *
 * **The file has to name every card exactly once.** A deck card missing from it is refused rather
 * than left at the end: an agent editing `card list` output that drops a line by accident would
 * otherwise reorder the deck around the hole and report success.
 *
 * **Re-runnable.** The same file again finishes a run that died partway, and writes nothing once
 * the deck is in that order.
 */
suspend fun cardReorder(
    args: Args,
    decks: DeckRepository,
    cards: CardRepository,
    onProgress: (String) -> Unit = {},
): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    val wanted = readOrderFile(args.requireOption("from-file"))

    val deck = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val current = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }.inStudyOrder().map { it.id }
    requireSameCards(deckId, current, wanted)

    val moved = wanted.indices.count { wanted[it] != current[it] }
    val dryRun = args.has(DRY_RUN_FLAG)
    val stored = if (dryRun) {
        deck
    } else {
        decks.reorderCards(deckId, wanted) { done, total -> onProgress("$done/$total chunk writes") }
            .getOrElse { throw asCliError(it) }
    }
    // The repository hands the deck back untouched when every record already held its cards in
    // order, which a run finishing an interrupted one does not: `moved` can be 0 and this true.
    val written = stored.updatedAt != deck.updatedAt

    return result(
        CardReorderResult(
            deckId = deckId,
            cardCount = stored.cardCount,
            moved = moved,
            written = written,
            chunks = stored.chunks.map { ChunkView(n = it.n, count = it.count, updatedAt = it.updatedAt) },
            dryRun = dryRun,
        ),
        when {
            dryRun -> "Would reorder $deckId: $moved of ${current.size} cards change position. Nothing written."
            written -> "Reordered $deckId: $moved of ${current.size} cards changed position."
            else -> "$deckId is already in that order — nothing written."
        },
    )
}

/**
 * Card ids, one a line, in the order wanted. Only the first tab-separated column is read, so the
 * output of plain `card list` can be reordered in an editor and handed straight back.
 */
internal fun readOrderFile(path: String): List<String> {
    val text = if (path == "-") {
        System.`in`.readBytes().toString(Charsets.UTF_8)
    } else {
        val file = File(path)
        if (!file.isFile) throw CliError(ExitCode.BadInput, "No such file: $path")
        file.readText(Charsets.UTF_8)
    }
    return parseOrder(text)
}

internal fun parseOrder(text: String): List<String> =
    text.lineSequence()
        .map { it.substringBefore('\t').trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()

private fun requireSameCards(deckId: String, current: List<String>, wanted: List<String>) {
    val duplicated = wanted.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val known = current.toSet()
    val unknown = wanted.filterNot { it in known }.distinct()
    val missing = known - wanted.toSet()
    if (duplicated.isEmpty() && unknown.isEmpty() && missing.isEmpty()) return

    val problems = listOfNotNull(
        duplicated.describe("named more than once"),
        unknown.describe("not in the deck"),
        missing.describe("in the deck and missing from the file"),
    )
    throw CliError(
        ExitCode.BadInput,
        "The file has to name each of $deckId's ${current.size} cards exactly once: " +
            problems.joinToString("; ") + ". Nothing was written.",
    )
}

private fun Collection<String>.describe(what: String): String? =
    takeIf { it.isNotEmpty() }?.let { ids ->
        val shown = ids.take(SHOWN_IDS).joinToString(", ")
        val more = if (ids.size > SHOWN_IDS) ", …" else ""
        "${ids.size} $what ($shown$more)"
    }

private const val SHOWN_IDS = 5

@Serializable
data class CardReorderResult(
    @SerialName("deck_id") val deckId: String,
    @SerialName("card_count") val cardCount: Int,
    /** Cards whose position differs from the one they had. */
    val moved: Int,
    /** False when every record already held its cards in the order asked for. */
    val written: Boolean,
    /** The chunk table as stored, so a caller can see the records are back at their normal size. */
    val chunks: List<ChunkView>,
    @SerialName("dry_run") val dryRun: Boolean = false,
)
