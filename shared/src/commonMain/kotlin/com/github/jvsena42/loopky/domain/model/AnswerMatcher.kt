package com.github.jvsena42.loopky.domain.model

/**
 * How forgiving a comparison of an answer to a card's expected text should be.
 *
 * The distinction is the whole reason this is a parameter rather than one fixed normalization:
 * a recognizer never had accents to get wrong in the first place, whereas typing "el zorro" for
 * "el zorró" is precisely the mistake a typed answer exists to surface.
 */
enum class AnswerStrictness {
    /** Diacritics folded away. Right for speech, where they were never the user's to type. */
    Lenient,

    /** Diacritics significant. Right for typing. */
    Strict,
}

/** How close a typed answer came. [NearMiss] is a strict miss that a [AnswerStrictness.Lenient] pass would have let through. */
enum class TypedAnswerOutcome { Correct, NearMiss, Wrong }

/**
 * Pure, testable comparison of an answer to a card's expected text.
 *
 * Both sides are lowercased, stripped of punctuation and whitespace-collapsed before comparison,
 * so "El Zorro!" matches "el zorro" — none of case, punctuation or spacing is what either study
 * mode is testing. Whether accents survive that is [AnswerStrictness]'s business.
 *
 * Framework-free so it unit-tests in commonTest and is reused across platforms.
 */
object AnswerMatcher {

    /**
     * Whether [expected] is something a reader could ever type their way past.
     *
     * Not the same as "not blank". A back of `"—"`, `"..."`, `"→"` or a lone emoji has text in it
     * but normalizes to nothing, and [matches] refuses an empty target — so no string on earth
     * matches such a card. Callers gating the typing mode must ask **this**, not `isNotBlank()`:
     * a card that cannot be answered correctly is a card the mode would trap you on.
     */
    fun isTypable(expected: String): Boolean =
        normalize(stripParentheticals(expected), AnswerStrictness.Strict).isNotEmpty()

    /**
     * Whether [given] answers [expected], ignoring any parenthesized aside on either side.
     *
     * See [stripParentheticals] for why the aside never counts — it is the same argument in both
     * modes, so the drop lives here rather than in each caller.
     *
     * A hyphen or slash makes the word boundary the writer's choice, so when one is present the
     * two sides are compared space-blind as well — see [joinsWords].
     */
    fun matches(given: String, expected: String, strictness: AnswerStrictness): Boolean {
        val target = normalize(stripParentheticals(expected), strictness)
        if (target.isEmpty()) return false
        val answer = normalize(stripParentheticals(given), strictness)
        if (answer == target) return true
        return joinsWords(expected, given) && answer.despace() == target.despace()
    }

    /**
     * Whether either text compounds two words with a joiner, leaving the space up to the writer.
     *
     * "self-presentation", "self presentation" and "selfpresentation" are one answer: dropping the
     * joiner as punctuation only settles the third, since it turns the first into "selfpresentation"
     * and leaves the second two words long. A recognizer picks a side on its own and a typist has
     * no way to know which the author wrote, so neither choice is theirs to fail on.
     *
     * The comparison stays gated on a joiner actually appearing: space-blind by default would let
     * "not able" answer "notable".
     */
    private fun joinsWords(vararg texts: String): Boolean =
        texts.any { text -> text.any { it in WORD_JOINERS } }

    /** Characters that join two words with no space around them. */
    private val WORD_JOINERS = charArrayOf(
        '-', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2212', '/',
    )

    private fun String.despace(): String = replace(" ", "")

    /**
     * Grade a typed answer, reserving [TypedAnswerOutcome.NearMiss] for the accent-only slip.
     *
     * A near miss is reported rather than scored: it reveals the answer and says which way it
     * differed. Nothing here picks an SRS grade — that stays the user's, whatever the outcome.
     *
     * A parenthesized aside on the card is not part of what has to be typed, so `"hello"` answers
     * `"hello (formal)"` outright — see [stripParentheticals].
     */
    fun judge(typed: String, expected: String): TypedAnswerOutcome = when {
        matches(typed, expected, AnswerStrictness.Strict) -> TypedAnswerOutcome.Correct
        matches(typed, expected, AnswerStrictness.Lenient) -> TypedAnswerOutcome.NearMiss
        else -> TypedAnswerOutcome.Wrong
    }

    /**
     * Drop parenthesized asides, so `"hello (formal)"` is graded as `"hello"`.
     *
     * A parenthetical on a card is a note to the reader — a register, a disambiguation, a part of
     * speech — not part of the phrase itself. Nobody says it out loud, and nobody should have to
     * type it: counting it fails an otherwise-perfect utterance and turns an editorial note into
     * four extra words to get exactly right. Punctuation stripping alone is not enough — it would
     * leave the word *inside* the brackets in the target.
     *
     * Returns [text] untouched when the asides are all there is, since a card whose whole text is
     * parenthesized still has to be answerable.
     */
    fun stripParentheticals(text: String): String {
        val stripped = text.replace(PARENTHETICAL, " ").replace(WHITESPACE, " ").trim()
        return if (normalize(stripped, AnswerStrictness.Strict).isEmpty()) text else stripped
    }

    /** Both ASCII and full-width brackets — CJK decks routinely use the latter. */
    private val PARENTHETICAL = Regex("""\([^()]*\)|（[^（）]*）""")

    /** Lowercase, drop non-alphanumeric chars, collapse whitespace; fold diacritics when lenient. */
    fun normalize(text: String, strictness: AnswerStrictness): String = buildString {
        for (ch in text.lowercase()) {
            val base = if (strictness == AnswerStrictness.Lenient) stripDiacritic(ch) else ch
            when {
                base.isLetterOrDigit() -> append(base)
                base.isWhitespace() -> append(' ')
                // drop everything else (punctuation, symbols)
            }
        }
    }.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")

    /** Map the common Latin accented letters used by supported languages to their base form. */
    private fun stripDiacritic(ch: Char): Char = when (ch) {
        'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'í', 'ì', 'î', 'ï' -> 'i'
        'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
        'ú', 'ù', 'û', 'ü' -> 'u'
        'ñ' -> 'n'
        'ç' -> 'c'
        else -> ch
    }
}
