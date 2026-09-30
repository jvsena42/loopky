package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.cliJson
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import kotlinx.coroutines.delay

/**
 * How a planned batch of card writes is sent, and what is reported when part of it does not land.
 *
 * Split from `Cards.kt`, which is the three commands and the planning they share: everything here
 * is about the write itself. The two writers differ only in the call they can use — an *add* only
 * ever lands at the end of the deck, so it appends in groups; an *edit* rewrites cards scattered
 * across the chunk table, so it has no cheaper form than one `upsertCard` each — and they share
 * one failure policy, which is the reason they sit together.
 */

/** One row of a batch, resolved into the card it will write. [row] is 1-based, as the file counts. */
internal class PlannedWrite(val row: Int, val card: Card)

/** What a batch did, for a person. Two spellings of "skipped": the reasons genuinely differ. */
internal enum class BatchVerb(val past: String, val skipped: String) {
    Add("Added", "already present"),
    Edit("Edited", "already up to date"),
}

/**
 * Write a planned batch of **new** cards, in groups, and report what landed.
 *
 * The counterpart to [applyBatch], which exists for `card edit` — an edit rewrites cards scattered
 * across the chunk table, so it has no cheaper form than one `upsertCard` each. An *add* only ever
 * lands at the end of the deck, which is exactly what `appendCards` is for.
 *
 * The reporting contract is [applyBatch]'s, unchanged: a failure still carries the [CardWriteResult]
 * on the failure envelope, so a caller knows which rows are on the homeserver. What differs is the
 * granularity — a group either lands whole or not at all, so a failed group's rows are all reported
 * failed, and the groups after it are not attempted.
 *
 * **A failed group is not retried in-process**, unlike a single `upsertCard`. `appendCards` writes
 * its chunk records before it patches the manifest and refuses an id already in the target chunk,
 * so a second attempt after a 500 on the patch reads its own cards back and fails an assertion —
 * exit 1 "internal" for a homeserver wobble. Re-running the command is the recovery, and it is a
 * correct one: the dedupe reads the chunk *records*, so cards a half-finished append left behind
 * are seen and skipped rather than written twice.
 */
@Suppress("LongParameterList")
internal suspend fun appendBatch(
    deckId: String,
    deck: Deck,
    decks: DeckRepository,
    cards: CardRepository,
    planned: List<PlannedWrite>,
    skipped: Int,
    imageChecks: List<ImageCheck>,
    imageAdvice: List<ImageAdvice>,
    onProgress: (String) -> Unit,
): CommandResult {
    val written = mutableListOf<Card>()
    val failures = mutableListOf<CardWriteFailure>()
    var latest = deck
    var notAttempted = 0
    var stopped: CliError? = null

    val groups = planned.chunked(APPEND_GROUP)
    for ((index, group) in groups.withIndex()) {
        if (stopped != null) {
            notAttempted += group.size
            continue
        }
        decks.appendCards(deckId, group.map { it.card }).fold(
            onSuccess = { updated ->
                latest = updated
                group.forEach { written += cards.stored(deckId, it.card) }
                onProgress("${written.size}/${planned.size} cards, ${index + 1}/${groups.size} groups")
            },
            onFailure = { error ->
                val failure = asCliError(error)
                failures += group.failedWith(failure)
                stopped = failure
            },
        )
    }

    val payload = CardWriteResult(
        deckId = deckId,
        cards = written.map { it.toView() },
        written = written.size,
        skipped = skipped,
        cardCount = latest.cardCount,
        failed = failures.size,
        failures = failures,
        notAttempted = notAttempted,
        imageChecks = imageChecks,
        imageAdvice = imageAdvice,
    )
    val text = buildString {
        append("${BatchVerb.Add.past} ${written.size} card(s)")
        if (skipped > 0) append(", skipped $skipped ${BatchVerb.Add.skipped}")
        if (failures.isNotEmpty()) append(", FAILED ${failures.size}")
        if (notAttempted > 0) append(", $notAttempted not attempted")
    }
    val failure = stopped ?: return result(payload, text)

    throw CliError(
        failure.exitCode,
        "$text. First failure was row ${failures.first().row}: ${failure.message} " +
            "Re-run the same file — rows already applied are skipped.",
        cliJson.encodeToJsonElement(CardWriteResult.serializer(), payload),
    )
}

/**
 * Every row of a group, marked failed with the same reason.
 *
 * All of them, because the append is one write: none landed, and reporting only the first would
 * leave the rest looking like they had.
 */
private fun List<PlannedWrite>.failedWith(failure: CliError): List<CardWriteFailure> = map {
    CardWriteFailure(
        row = it.row,
        cardId = it.card.id,
        code = failure.exitCode.json,
        message = failure.message.orEmpty(),
    )
}

/**
 * Cards appended in one `appendCards` call.
 *
 * Mirrors the shared `CHUNK_SIZE` so a group is about one chunk record plus one manifest patch,
 * but nothing depends on the two matching: a group larger or smaller than a chunk still writes
 * correctly, it only changes how much work a failure costs.
 */
private const val APPEND_GROUP = 100

/**
 * Write a planned batch, and report what landed whether or not all of it did.
 *
 * Three rules, all from the same failure (#229, item 2): a 665-row `card edit --from-file` that
 * 500'd after 35 writes, said nothing about the 35, and had no way to pick up where it stopped.
 *
 * - **A row failure does not end the batch.** The rows after it are attempted, because the ones
 *   that worked when sent singly were the same rows.
 * - **A batch-ending failure does.** An expired session, a full disk or an unsupported host will
 *   refuse every remaining row identically, and 600 more round trips against it buy nothing — so
 *   the batch stops and reports how many it never reached. So does a run of consecutive failures,
 *   which is what a homeserver in trouble looks like from here.
 * - **A failed batch still reports what it wrote.** The result travels on the failure envelope as
 *   `data`, ids included, so the caller knows which rows are on the homeserver.
 */
@Suppress("LongParameterList")
internal suspend fun applyBatch(
    deckId: String,
    deck: Deck,
    decks: DeckRepository,
    cards: CardRepository,
    planned: List<PlannedWrite>,
    skipped: Int,
    verb: BatchVerb,
    imageChecks: List<ImageCheck>,
    imageAdvice: List<ImageAdvice>,
): CommandResult {
    val written = mutableListOf<Card>()
    val failures = mutableListOf<CardWriteFailure>()
    var latest = deck
    var consecutiveFailures = 0
    var stopped: CliError? = null
    var index = 0

    while (index < planned.size) {
        val entry = planned[index]
        val result = writeWithRetry(decks, deckId, entry.card)
        result.onSuccess {
            latest = it
            written += cards.stored(deckId, entry.card)
            consecutiveFailures = 0
        }.onFailure { error ->
            val failure = asCliError(error)
            failures += CardWriteFailure(
                row = entry.row,
                cardId = entry.card.id,
                code = failure.exitCode.json,
                message = failure.message.orEmpty(),
            )
            consecutiveFailures++
            if (failure.exitCode in BATCH_ENDING || consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                stopped = failure
            }
        }
        index++
        if (stopped != null) break
    }

    val notAttempted = planned.size - index
    val payload = CardWriteResult(
        deckId = deckId,
        cards = written.map { it.toView() },
        written = written.size,
        skipped = skipped,
        cardCount = latest.cardCount,
        failed = failures.size,
        failures = failures,
        notAttempted = notAttempted,
        imageChecks = imageChecks,
        imageAdvice = imageAdvice,
    )
    val text = buildString {
        append("${verb.past} ${written.size} card(s)")
        if (skipped > 0) append(", skipped $skipped ${verb.skipped}")
        if (failures.isNotEmpty()) append(", FAILED ${failures.size}")
        if (notAttempted > 0) append(", $notAttempted not attempted")
    }
    if (failures.isEmpty()) return result(payload, text)

    throw CliError(
        // The failure that *ended* the batch when there was one, since that is the state the whole
        // run is now in — a full disk after four unrelated 500s is a full disk, not a 500.
        stopped?.exitCode ?: failures.first().exitCode(),
        "$text. First failure was row ${failures.first().row} (card ${failures.first().cardId}): " +
            "${failures.first().message} Re-run the same file — rows already applied are skipped.",
        cliJson.encodeToJsonElement(CardWriteResult.serializer(), payload),
    )
}

/**
 * One card write, retried through a homeserver that answered 5xx.
 *
 * The shared layer already retries an expiry, a 429 and an unreachable session round trip
 * (`withWriteRetry`); a 500 is the gap, and it is the one this saw. Bounded and short: three
 * attempts, because a homeserver that is still 500ing after two seconds is not going to be talked
 * round by a fourth, and a 665-row batch cannot afford a long backoff per row.
 */
private suspend fun writeWithRetry(decks: DeckRepository, deckId: String, card: Card): Result<Deck> {
    var backoff = RETRY_BACKOFF_MS
    repeat(WRITE_ATTEMPTS - 1) {
        val result = decks.upsertCard(deckId, card)
        val error = result.exceptionOrNull() ?: return result
        if (ExitCode.of(error) != ExitCode.ServerError) return result
        delay(backoff)
        backoff *= 2
    }
    return decks.upsertCard(deckId, card)
}

private fun CardWriteFailure.exitCode(): ExitCode =
    ExitCode.entries.firstOrNull { it.json == code } ?: ExitCode.Internal

/**
 * Failures that will refuse every remaining row the same way, so attempting them buys nothing but
 * round trips. [ExitCode.NotFound] is deliberately absent — that is one missing card, not a dead
 * batch.
 */
private val BATCH_ENDING = setOf(
    ExitCode.NotSignedIn,
    ExitCode.SessionExpired,
    ExitCode.StorageFull,
    ExitCode.EnvironmentMismatch,
    ExitCode.UnsupportedHost,
)

/** What a homeserver in trouble looks like from here: not one bad row, but a batch worth stopping. */
private const val MAX_CONSECUTIVE_FAILURES = 5

private const val WRITE_ATTEMPTS = 3
private const val RETRY_BACKOFF_MS = 500L

/**
 * The card as the homeserver has it, falling back to what was sent.
 *
 * `upsertCard` **discards the caller's `ord`** and recomputes one from the chunk it lands in, so
 * echoing the local `Card` reports intent rather than result — on an empty deck the CLI said 1000
 * where 0 was stored. For a channel whose purpose is diffing intent against result, that is the one
 * thing it set out not to do. A cache read, not a round trip: the write just populated it.
 */
private suspend fun CardRepository.stored(deckId: String, sent: Card): Card =
    get(deckId, sent.id) ?: sent
