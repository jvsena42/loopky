package com.github.jvsena42.loopky.domain.model

/**
 * Where a card side's editorial notes are — the text [AnswerMatcher.stripParentheticals] drops
 * before grading. `(…)` and `（…）` anywhere; square brackets under the rules in [squareAsides].
 */
internal object Asides {

    /** The asides as written — what [CardChecks] reads for a leaked answer. */
    fun of(text: String): List<String> = ranges(text).map { text.substring(it) }

    /** Sorted by start; a bracket nested in another is listed too. */
    fun ranges(text: String): List<IntRange> =
        (ROUND_OR_FULL_WIDTH.findAll(text).map { it.range } + squareAsides(text)).sortedBy { it.first }.toList()

    /**
     * ASCII `[…]` notes (`hello [ˈhɛloʊ][noun]`, `[informal] hola`). Unlike `(…)` a square bracket
     * is often the answer itself — an index, a concentration, an interval, Anki furigana — so it
     * counts only as a run of brackets at the very start or end of the side, set off from the text
     * by a space, holding no digit or operator and not following one — and never on a side with an
     * operator anywhere, which is a formula (`rate = k [A]`). `arr[0]`, `-log [H+]`,
     * `Ka = [H+][A-] / [HA]`, `x ∈ [0, 1]` and `日本[にほん]語[ご]` all stay answer text.
     */
    private fun squareAsides(text: String): List<IntRange> {
        if (text.any { it.isOperator() }) return emptyList()
        val brackets = SQUARE.findAll(text).map { it.range }.toList()
        val leading = brackets.takeChain(text, fromStart = true)
        val trailing = brackets.drop(leading.size).asReversed().takeChain(text, fromStart = false)
        val leadingOk = leading.isNotEmpty() && text.getOrNull(leading.last().last + 1)?.isWhitespace() != false
        val trailingOk = trailing.isNotEmpty() && setOff(text, trailing.minOf { it.first })
        return (if (leadingOk) leading else emptyList()) + (if (trailingOk) trailing else emptyList())
    }

    /** The brackets from one end of [text] with only whitespace between them, each holding a note. */
    private fun List<IntRange>.takeChain(text: String, fromStart: Boolean): List<IntRange> {
        val chain = mutableListOf<IntRange>()
        var edge = if (fromStart) 0 else text.length
        for (range in this) {
            val gap = if (fromStart) text.substring(edge, range.first) else text.substring(range.last + 1, edge)
            if (gap.isNotBlank() || !isNote(text.substring(range.first + 1, range.last))) break
            chain += range
            edge = if (fromStart) range.last + 1 else range.first
        }
        return chain
    }

    private fun setOff(text: String, start: Int): Boolean {
        if (start == 0 || !text[start - 1].isWhitespace()) return false
        val before = text.substring(0, start).trimEnd().lastOrNull() ?: return false
        return !before.isOperator() && before != '-'
    }

    private fun isNote(content: String): Boolean {
        val inner = content.trim()
        return inner.none { it.isDigit() || it.isOperator() } && !inner.startsWith('-') && !inner.endsWith('-')
    }

    private fun Char.isOperator(): Boolean = this in OPERATORS || category == CharCategory.MATH_SYMBOL

    private const val OPERATORS = "+=*/^<>±×÷|"

    /**
     * Full-width `［］` is never code, so it counts anywhere, like `(…)`. Neither kind of square
     * bracket is a reading for [AnswerMatcher]'s readings: Anki writes furigana per segment
     * (`日本[にほん]語[ご]`), so one would let `ご` answer 日本語.
     */
    private val ROUND_OR_FULL_WIDTH = Regex("""\([^()]*\)|（[^（）]*）|［[^［］]*］""")

    private val SQUARE = Regex("""\[[^\[\]]*]""")
}
