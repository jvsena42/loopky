package com.github.jvsena42.loopky.domain.model

/** What [CardChecks] can call wrong about a card without knowing what the deck is about. */
enum class CardRule {
    /** Several cards show the same front and want different answers. */
    DuplicateFront,

    /** With reverse on, several cards show the same back and want different answers. */
    DuplicateBack,

    /** A parenthesized aside on the prompt contains a word of the answer. */
    AsideHoldsAnswer,

    /** The answer lists alternatives with a slash, which typing then expects as written. */
    Alternatives,

    /** The answer has text and nothing a typed or spoken reply could match. */
    NothingToGrade,
}

/** [cards] are positions in the list that was checked, in order. */
data class CardFinding(val rule: CardRule, val cards: List<Int>)

/** Which of a deck's study opt-ins decide what a card has to survive. */
data class StudyModes(
    val reverse: Boolean,
    /** Type or Speak: a reply is compared with the answer rather than self-graded. */
    val graded: Boolean,
) {
    constructor(deck: Deck) : this(deck.reverseEnabled, deck.typeEnabled || deck.speakEnabled)
}

/** One run of consecutive cards and how its answers split between single words and longer ones. */
data class CompositionStretch(val from: Int, val to: Int, val words: Int, val phrases: Int, val untexted: Int)

/**
 * The card-writing rules that can be decided from the cards alone.
 *
 * They were prose in the agent skill and nowhere else, so a deck of thousands of cards written
 * in batches could break every one of them and publish with nothing said — a front asked twice
 * with two answers is graded wrong half the time, and nobody reads 5,000 cards to find it.
 *
 * **Findings, never refusals.** Each rule has honest exceptions (a deck of minimal pairs, a card
 * about the slash itself), and a check that can be wrong must not be able to stop a publish.
 * Framework-free, so the apps can surface the same findings in an editor.
 */
object CardChecks {

    /**
     * Findings over [cards]. With [addedFrom], only those a card at or past that position is part
     * of: the cards before it are a deck already published, which is searched for a prompt a new
     * card repeats and is otherwise not re-examined — adding one card to a deck of 20,000 must
     * not re-check 20,000 cards for asides.
     */
    fun check(cards: List<Card>, modes: StudyModes, addedFrom: Int = 0): List<CardFinding> = buildList {
        val added = addedFrom until cards.size
        addAll(duplicates(cards, addedFrom, CardRule.DuplicateFront, prompt = { it.front }, answer = { it.back }))
        if (modes.reverse) {
            addAll(duplicates(cards, addedFrom, CardRule.DuplicateBack, prompt = { it.back }, answer = { it.front }))
        }
        addAll(perCard(cards, added, CardRule.AsideHoldsAnswer, modes.reverse, ::asideHoldsAnswer))
        addAll(perCard(cards, added, CardRule.Alternatives, modes.reverse) { _, answer -> listsAlternatives(answer) })
        if (modes.graded) {
            addAll(perCard(cards, added, CardRule.NothingToGrade, modes.reverse) { _, answer -> hasNothingToGrade(answer) })
        }
    }

    /**
     * [cards] in stretches of [FINE_STRETCH] up to [FINE_UNTIL] and of [COARSE_STRETCH] after it.
     *
     * A share measured over a whole deck hides its shape: a list ranked by frequency puts every
     * function word first and every phrase last, and reads as balanced in total. A phrase is an
     * answer of more than one word once asides are dropped, so the split means nothing in a
     * script that writes no spaces.
     */
    fun composition(cards: List<Card>): List<CompositionStretch> {
        val stretches = mutableListOf<CompositionStretch>()
        var from = 0
        while (from < cards.size) {
            val size = if (from < FINE_UNTIL) FINE_STRETCH else COARSE_STRETCH
            val to = minOf(from + size, cards.size)
            val answers = cards.subList(from, to).map { answerWords(it.back.text) }
            stretches += CompositionStretch(
                from = from + 1,
                to = to,
                words = answers.count { it == 1 },
                phrases = answers.count { it > 1 },
                untexted = answers.count { it == 0 },
            )
            from = to
        }
        return stretches
    }

    private fun answerWords(text: String?): Int =
        tokens(AnswerMatcher.stripParentheticals(text.orEmpty())).size

    /** One finding per prompt that several cards share while asking for different answers. */
    private fun duplicates(
        cards: List<Card>,
        addedFrom: Int,
        rule: CardRule,
        prompt: (Card) -> CardSide,
        answer: (Card) -> CardSide,
    ): List<CardFinding> =
        cards.indices
            .filterNot { prompt(cards[it]).isEmpty }
            .groupBy { prompt(cards[it]).key() }
            .values
            .filter { group -> group.last() >= addedFrom }
            .filter { group -> group.map { answer(cards[it]).key() }.distinct().size > 1 }
            .map { CardFinding(rule, it) }

    /**
     * What makes two sides the same side: the text **as written**, case and spacing aside, and
     * the picture. Not [AnswerMatcher.normalize], which keeps letters and digits only — right for
     * grading a reply and wrong here, where it makes `C++` and `C#` one prompt, drops the vowel
     * signs that tell Hindi and Thai words apart, and reduces every emoji to nothing. The aside
     * stays in for the same reason: it is what tells `começar` from `começar (formal)`. So does
     * the picture, or ten flags under "Whose flag is this?" are one prompt.
     */
    private fun CardSide.key(): String =
        text.orEmpty().trim().lowercase().replace(SPACES, " ") + KEY_SEPARATOR +
            imageRef?.let { it.url ?: it.sha256 }.orEmpty()

    /** [test] over each card's prompt and answer, and over the swapped pair when the deck reverses. */
    private fun perCard(
        cards: List<Card>,
        among: IntRange,
        rule: CardRule,
        reverse: Boolean,
        test: (prompt: String, answer: String) -> Boolean,
    ): List<CardFinding> =
        among.filter { index ->
            val front = cards[index].front.text.orEmpty()
            val back = cards[index].back.text.orEmpty()
            test(front, back) || (reverse && test(back, front))
        }.map { CardFinding(rule, listOf(it)) }

    private fun asideHoldsAnswer(prompt: String, answer: String): Boolean {
        val wanted = tokens(AnswerMatcher.stripParentheticals(answer)).filter { it.length >= MIN_LEAKED_WORD }.toSet()
        if (wanted.isEmpty()) return false
        return ASIDE.findAll(prompt).any { aside -> tokens(aside.value).any { it in wanted } }
    }

    private fun listsAlternatives(answer: String): Boolean {
        val stripped = AnswerMatcher.stripParentheticals(answer)
        return SPACED_SLASH.containsMatchIn(stripped) || WORD_SLASH_WORD.matches(stripped.trim())
    }

    private fun hasNothingToGrade(answer: String): Boolean =
        answer.isNotBlank() && !AnswerMatcher.isTypable(answer)

    private fun tokens(text: String): List<String> =
        AnswerMatcher.normalize(text, AnswerStrictness.Lenient).split(' ').filter { it.isNotEmpty() }

    private val ASIDE = Regex("""\([^()]*\)|（[^（）]*）""")
    private val SPACES = Regex("""\s+""")
    private val SPACED_SLASH = Regex("""\S\s+/\s+\S""")
    private val WORD_SLASH_WORD = Regex("""\p{L}{2,}/\p{L}{2,}""")

    /** Shorter than this and the "leak" is an article or a preposition both languages share. */
    private const val MIN_LEAKED_WORD = 3
    private const val KEY_SEPARATOR = "\u0000"
    private const val FINE_STRETCH = 100
    private const val FINE_UNTIL = 500
    private const val COARSE_STRETCH = 500
}
