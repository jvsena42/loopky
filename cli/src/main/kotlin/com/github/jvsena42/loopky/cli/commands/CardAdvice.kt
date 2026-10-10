package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardChecks
import com.github.jvsena42.loopky.domain.model.CardRule
import com.github.jvsena42.loopky.domain.model.CompositionStretch
import com.github.jvsena42.loopky.domain.model.StudyModes
import kotlinx.serialization.Serializable

/**
 * A card-writing rule one or more cards break, decided from the cards alone. See [CardChecks].
 *
 * Advice like [ImageAdvice], and for the same reason never a refusal: each rule has honest
 * exceptions, and a check that can be wrong must not be able to stop a publish. It rides in
 * `--json` because an agent publishing 5,000 cards reads neither them nor stderr's capped block.
 */
@Serializable
data class CardAdvice(
    /** `duplicate_front`, `duplicate_back`, `aside_holds_answer`, `alternatives`, `nothing_to_grade`. */
    val rule: String,
    /** The cards it is about: `Card 12` for a row of the file, `card <id>` for one already in the deck. */
    val where: List<String>,
    val advice: String,
)

/** How one stretch of the file splits between one-word answers and longer ones. See [CardChecks.composition]. */
@Serializable
data class CompositionView(val from: Int, val to: Int, val words: Int, val phrases: Int, val untexted: Int)

internal fun List<Card>.compositionView(): List<CompositionView> =
    CardChecks.composition(this).map(CompositionStretch::toView)

private fun CompositionStretch.toView() = CompositionView(from, to, words, phrases, untexted)

/**
 * [CardChecks] over [cards], as advice. [label] names the card at a position; [worthSaying] drops
 * a finding nobody asked about — for `card add`, one that involves only cards already in the deck.
 */
internal fun cardAdvice(
    cards: List<Card>,
    modes: StudyModes,
    label: (Int) -> String = { "Card ${it + 1}" },
    worthSaying: (List<Int>) -> Boolean = { true },
): List<CardAdvice> =
    CardChecks.check(cards, modes)
        .filter { worthSaying(it.cards) }
        .map { CardAdvice(it.rule.json, it.cards.map(label), it.rule.advice) }

private val CardRule.json: String
    get() = when (this) {
        CardRule.DuplicateFront -> "duplicate_front"
        CardRule.DuplicateBack -> "duplicate_back"
        CardRule.AsideHoldsAnswer -> "aside_holds_answer"
        CardRule.Alternatives -> "alternatives"
        CardRule.NothingToGrade -> "nothing_to_grade"
    }

private val CardRule.advice: String
    get() = when (this) {
        CardRule.DuplicateFront ->
            "these cards show the same front and want different answers, so a correct reply to one is " +
                "graded wrong on the other. Tell them apart with a short aside in brackets, or keep one."

        CardRule.DuplicateBack ->
            "with --reverse on, these cards show the same back and want different answers. Give each " +
                "sense a short aside in brackets on the back, or keep one."

        CardRule.AsideHoldsAnswer ->
            "an aside in brackets contains a word of the card's own answer, which gives it away."

        CardRule.Alternatives ->
            "the answer lists alternatives with a slash, and a typed answer has to match it as written. " +
                "Make it two cards, or keep one form."

        CardRule.NothingToGrade ->
            "the answer has no letters or digits for a typed or spoken reply to match, so the card " +
                "falls back to tap to reveal."
    }

/** One block on stderr, capped like the picture advice: every row is also in `--json`. */
internal fun List<CardAdvice>.reportCardAdvice(onNote: (String) -> Unit) {
    if (isEmpty()) return
    onNote("loopky: $size finding(s) about the cards themselves — advice, nothing was refused:")
    take(MAX_REPORTED_CARD_ADVICE).forEach { onNote("loopky:   ${it.whereSummary()} — ${it.advice}") }
    if (size > MAX_REPORTED_CARD_ADVICE) {
        onNote("loopky:   … and ${size - MAX_REPORTED_CARD_ADVICE} more — every one is in --json card_advice.")
    }
}

private fun CardAdvice.whereSummary(): String = when {
    where.size <= MAX_REPORTED_CARDS -> where.joinToString(", ")
    else -> where.take(MAX_REPORTED_CARDS).joinToString(", ") + " and ${where.size - MAX_REPORTED_CARDS} more"
}

private const val MAX_REPORTED_CARD_ADVICE = 20
private const val MAX_REPORTED_CARDS = 4
