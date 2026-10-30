package com.github.jvsena42.loopky.domain.model

/**
 * Outcome of comparing a spoken utterance against the expected card text.
 *
 * @property correct whether the normalized utterance matched the expected text
 * @property heard the raw transcript the recognizer returned (shown on the "wrong" sheet)
 * @property expected the raw expected text (shown on the "wrong" sheet)
 */
data class SpeakResult(
    val correct: Boolean,
    val heard: String,
    val expected: String,
)

/**
 * Pronunciation practice's view of [AnswerMatcher] — the same normalization, fixed to
 * [AnswerStrictness.Lenient] and with [NumberWords] folded in.
 *
 * Speech is graded leniently because a recognizer emits whatever text its model settles on: the
 * accents in that transcript were never the speaker's to get right, and neither was its choice
 * between `"10"` and `"ten"`. Typing is graded strictly for the mirror-image reason.
 */
object SpeakMatcher {

    /**
     * Grade [spoken] against [expected] — leniently, past any parenthesized aside on the card, and
     * with spelled-out numbers read as digits in [languageTag]'s language.
     *
     * `"hello (formal)"` is graded as `"hello"`; only the *grading* drops the aside.
     * [SpeakResult.expected] keeps the card's text as written, because the note is exactly the
     * context worth showing on the "wrong" sheet.
     *
     * [languageTag] is the declared language of the side being graded, and a null one folds no
     * numbers at all — the same refusal to guess a locale that gates the feature in the first
     * place. Number words only mean what they mean in a known language; see [NumberWords].
     */
    fun match(spoken: String, expected: String, languageTag: String? = null): SpeakResult =
        SpeakResult(
            correct = AnswerMatcher.matches(
                given = NumberWords.fold(spoken, languageTag),
                expected = NumberWords.fold(expected, languageTag),
                strictness = AnswerStrictness.Lenient,
                languageTag = languageTag,
            ),
            heard = spoken.trim(),
            expected = expected.trim(),
        )

    fun normalize(text: String, languageTag: String? = null): String = AnswerMatcher.normalize(
        AnswerMatcher.stripParentheticals(NumberWords.fold(text, languageTag)),
        AnswerStrictness.Lenient,
    )
}
