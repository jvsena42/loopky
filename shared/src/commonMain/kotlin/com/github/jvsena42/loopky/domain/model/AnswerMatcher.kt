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
     *
     * [languageTag] is the declared language of the side being graded. Japanese, Chinese and
     * Korean compare space-blind, and in Japanese and Chinese a kana or pinyin aside is a second
     * accepted answer (`ねこ` answers `猫（ねこ）`) — see [readingTargets]. Keyed on the declared
     * language and nothing else, so `hello (formal)` never accepts `formal` (#554).
     */
    fun matches(
        given: String,
        expected: String,
        strictness: AnswerStrictness,
        languageTag: String? = null,
    ): Boolean {
        val target = normalize(stripParentheticals(expected), strictness)
        if (target.isEmpty()) return false
        val answer = normalize(stripParentheticals(given), strictness)
        val script = CjkScript.of(languageTag)
        val spaceBlind = script != null || joinsWords(expected, given)
        val targets = listOf(target) + readingTargets(expected, script).map { normalize(it, strictness) }
        return targets.any { it.isNotEmpty() && (answer == it || spaceBlind && answer.despace() == it.despace()) }
    }

    /**
     * The asides of [expected] that spell its reading: pure kana in Japanese, pure pinyin in
     * Chinese — tone-marked, since toneless pinyin cannot be told from a note like `(formal)`. A reading is the word itself in another script, so typing or saying it is
     * recall — the one deliberate exception to "an aside is never the answer". Korean has none:
     * Hangul is already phonetic.
     */
    private fun readingTargets(expected: String, script: CjkScript?): List<String> {
        if (script == null || script == CjkScript.Korean) return emptyList()
        return PARENTHETICAL.findAll(foldWidth(expected))
            .map { it.value.drop(1).dropLast(1).trim() }
            .filter { aside ->
                val toned = script != CjkScript.Chinese || aside.any { it.lowercaseChar() in PINYIN_TONED }
                toned && aside.any { it.isLetter() } && aside.all { ch ->
                    ch.isWhitespace() || ch in READING_PUNCTUATION || when (script) {
                        CjkScript.Japanese -> ch.isKana()
                        CjkScript.Chinese -> ch.isPinyinLetter()
                        CjkScript.Korean -> false
                    }
                }
            }
            .toList()
    }

    private const val PINYIN_TONED = "āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ"

    private val READING_PUNCTUATION = "'’·・。、.,!?！？-".toSet()

    private fun Char.isKana(): Boolean = this in '\u3041'..'\u30FF' && this != '\u30FB'

    private fun Char.isPinyinLetter(): Boolean {
        val base = stripDiacritic(lowercaseChar())
        return base in 'a'..'z'
    }

    /** The CJK languages, whose words are not delimited by spaces the way Latin ones are. */
    private enum class CjkScript {
        Japanese,
        Chinese,
        Korean,
        ;

        companion object {
            fun of(languageTag: String?): CjkScript? =
                when (languageTag?.substringBefore('-')?.trim()?.lowercase()) {
                    "ja" -> Japanese
                    "zh" -> Chinese
                    "ko" -> Korean
                    else -> null
                }
        }
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
    fun judge(typed: String, expected: String, languageTag: String? = null): TypedAnswerOutcome = when {
        matches(typed, expected, AnswerStrictness.Strict, languageTag) -> TypedAnswerOutcome.Correct
        matches(typed, expected, AnswerStrictness.Lenient, languageTag) -> TypedAnswerOutcome.NearMiss
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

    /**
     * Fold width, lowercase, drop non-alphanumeric chars, collapse whitespace; fold diacritics when
     * lenient.
     */
    fun normalize(text: String, strictness: AnswerStrictness): String = buildString {
        for (ch in foldWidth(text).lowercase()) {
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
        // Pinyin tone marks, so a toneless `xuexi` is a near miss for `xuéxí` rather than wrong.
        'ā', 'ǎ' -> 'a'
        'ē', 'ě' -> 'e'
        'ī', 'ǐ' -> 'i'
        'ō', 'ǒ' -> 'o'
        'ū', 'ǔ', 'ǖ', 'ǘ', 'ǚ', 'ǜ' -> 'u'
        else -> ch
    }

    /**
     * NFKC's width fold, by table: full-width ASCII (`１`, `Ａ`) to ASCII, the ideographic space to
     * a space, and half-width katakana (`ｶﾞ`) to the standard form, voicing marks merged. A
     * recognizer or an IME picks the width, never the reader (#554). `commonMain` has no
     * normalizer — the same reason [TagLabels] folds by table.
     */
    fun foldWidth(text: String): String {
        if (text.none { it == '\u3000' || it in '\uFF01'..'\uFF9F' }) return text
        val out = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '\u3000' -> out.append(' ')
                in '\uFF01'..'\uFF5E' -> out.append(ch - FULL_WIDTH_OFFSET)
                in '\uFF66'..'\uFF9D' -> out.append(HALF_WIDTH_KATAKANA[ch - '\uFF66'])
                '\uFF9E', '\uFF9F' -> voice(out, semi = ch == '\uFF9F')
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /** Merge a half-width (semi-)voicing mark into the kana before it; a stray mark is dropped. */
    private fun voice(out: StringBuilder, semi: Boolean) {
        val last = out.lastOrNull() ?: return
        val voiced = when {
            semi && last in HA_ROW -> last + 2
            !semi && last == 'ウ' -> 'ヴ'
            !semi && last in VOICEABLE -> last + 1
            else -> return
        }
        out.setCharAt(out.lastIndex, voiced)
    }

    private const val FULL_WIDTH_OFFSET = 0xFEE0

    /** U+FF66..U+FF9D in order. */
    private const val HALF_WIDTH_KATAKANA =
        "ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン"

    private const val HA_ROW = "ハヒフヘホ"

    /** Katakana whose voiced form is the next code point. */
    private const val VOICEABLE = "カキクケコサシスセソタチツテト" + HA_ROW
}
