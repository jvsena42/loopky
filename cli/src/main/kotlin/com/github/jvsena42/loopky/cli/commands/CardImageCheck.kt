package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val CARD_CHECK_IMAGES = "card check-images"

@Serializable
data class CardImageCheckResult(
    @SerialName("deck_id") val deckId: String,
    /** Distinct picture URLs asked about. A blob image has no URL and is not one of them. */
    val checked: Int,
    val ok: Int,
    val wrong: Int,
    val unverified: Int,
    /** Only what is worth reading, as on a write: a URL that answered with an image has no row. */
    @SerialName("image_checks") val imageChecks: List<CardImageCheck>,
)

/** An [ImageCheck], plus the cards to fix when it is wrong. */
@Serializable
data class CardImageCheck(
    val url: String,
    val status: Int?,
    @SerialName("content_type") val contentType: String?,
    val unverified: Boolean,
    val reason: String?,
    @SerialName("card_ids") val cardIds: List<String>,
)

/**
 * `--check-images` over the pictures a deck **already has**, writing nothing.
 *
 * The flag only ever asks about the rows a write is about to send, so a check a host rate-limited
 * — or one nobody ran — could be finished only by hand, outside the CLI (#454). It also answers a
 * question the flag cannot: whether a picture that was fine at publish time still is.
 *
 * Exit 0 whatever it finds, as on a write: the answer is the counts, and a dead URL is a finding
 * about the deck rather than a failure of the command.
 */
suspend fun cardCheckImages(
    args: Args,
    decks: DeckRepository,
    cards: CardRepository,
    onNote: (String) -> Unit = System.err::println,
    check: suspend (Collection<String>) -> List<ImageCheck> = { urls ->
        checkImageUrls(urls, onNote, args.imageCheckConcurrency(), writes = false)
    },
): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    // Before the deck is read, so a bad `--check-images-concurrency` costs no round trip.
    args.imageCheckConcurrency()
    val deck = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val cardsByUrl = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }
        .flatMap { card -> listOfNotNull(card.front.imageRef?.url, card.back.imageRef?.url).map { it to card.id } }
        .groupBy({ it.first }, { it.second })

    val problems = (if (cardsByUrl.isEmpty()) emptyList() else check(cardsByUrl.keys)).map {
        CardImageCheck(it.url, it.status, it.contentType, it.unverified, it.reason, cardsByUrl[it.url].orEmpty().distinct())
    }
    val unverified = problems.count { it.unverified }
    val wrong = problems.size - unverified
    val payload = CardImageCheckResult(
        deckId = deckId,
        checked = cardsByUrl.size,
        ok = cardsByUrl.size - problems.size,
        wrong = wrong,
        unverified = unverified,
        imageChecks = problems,
    )
    val text = buildString {
        if (cardsByUrl.isEmpty()) {
            append("Deck $deckId has no picture URLs to check.")
            return@buildString
        }
        // Unverified first and the summary last: a terminal keeps its last lines.
        problems.sortedByDescending { it.unverified }.forEach { appendLine(it.toLine()) }
        append("$deckId: ${payload.ok} ok, $wrong wrong, $unverified could not be checked. Nothing was written.")
    }
    return result(payload, text)
}

private fun CardImageCheck.toLine(): String = buildString {
    append(if (unverified) "unverified" else "wrong")
    append('\t').append(url)
    append('\t').append(listOfNotNull(reason, status?.let { "HTTP $it" }, contentType).joinToString(", "))
    append('\t').append(cardIds.joinToString(","))
}
