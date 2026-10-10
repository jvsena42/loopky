package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CardChecksTest {

    private val plain = StudyModes(reverse = false, graded = false)
    private val reversed = StudyModes(reverse = true, graded = false)
    private val graded = StudyModes(reverse = false, graded = true)

    private fun card(front: String, back: String, frontImage: String? = null) = Card(
        id = "$front|$back|$frontImage",
        deckId = "d1",
        updatedAt = 0L,
        front = CardSide(text = front, imageRef = frontImage?.let(::remoteImageRef)),
        back = CardSide(text = back),
    )

    private fun rules(modes: StudyModes, vararg cards: Card) =
        CardChecks.check(cards.toList(), modes).map { it.rule to it.cards }

    @Test
    fun oneFrontAskedForTwoAnswersIsFound() {
        assertEquals(
            listOf(CardRule.DuplicateFront to listOf(0, 2)),
            rules(plain, card("casa", "house"), card("rua", "street"), card(" Casa ", "home")),
        )
    }

    @Test
    fun anAsideOrAPictureMakesAFrontItsOwn() {
        assertEquals(emptyList(), rules(plain, card("começar", "start"), card("começar (formal)", "commence")))
        assertEquals(
            emptyList(),
            rules(
                plain,
                card("Whose flag is this?", "Brazil", "https://flagcdn.com/w640/br.png"),
                card("Whose flag is this?", "Chile", "https://flagcdn.com/w640/cl.png"),
            ),
        )
    }

    /** A prompt is compared as written. Reduced to letters and digits, each pair here is one prompt. */
    @Test
    fun promptsThatDifferOnlyInSymbolsMarksOrEmojiAreDifferentPrompts() {
        val pairs = listOf(
            card("C++", "compiled") to card("C#", "managed"),
            card("🇧🇷", "Brazil") to card("🇫🇷", "France"),
            card("कल", "tomorrow") to card("काल", "time"),
            card("มา", "to come") to card("ม้า", "horse"),
            card("+", "plus") to card("−", "minus"),
            card("casa", "house") to card("casa?", "is it a house?"),
        )
        pairs.forEach { (one, other) -> assertEquals(emptyList(), rules(reversed, one, other), "${one.front.text}") }
        assertEquals(
            listOf(CardRule.DuplicateFront to listOf(0, 1)),
            rules(plain, card("🇧🇷", "Brazil"), card("🇧🇷", "Brasil")),
        )
    }

    @Test
    fun onlyFindingsAnAddedCardIsPartOfAreReported() {
        val deck = listOf(card("gato", "cat"), card("gato", "tomcat"), card("ou", "either / or"), card("casa", "house"))
        val added = listOf(card("casa", "home"), card("ou (either)", "either"))

        val found = CardChecks.check(deck + added, plain, addedFrom = deck.size).map { it.rule to it.cards }

        // Not the deck's own `gato` pair or its slashed answer: nobody asked about those.
        assertEquals(listOf(CardRule.DuplicateFront to listOf(3, 4), CardRule.AsideHoldsAnswer to listOf(5)), found)
    }

    @Test
    fun theSameCardTwiceIsNotTwoAnswers() {
        assertEquals(emptyList(), rules(plain, card("casa", "house"), card("casa", "House")))
    }

    @Test
    fun aSharedBackOnlyMattersWhenTheDeckReverses() {
        val senses = arrayOf(card("banco", "bank"), card("margem", "bank"))
        assertEquals(emptyList(), rules(plain, *senses))
        assertEquals(listOf(CardRule.DuplicateBack to listOf(0, 1)), rules(reversed, *senses))
        assertEquals(emptyList(), rules(reversed, card("banco", "bank (money)"), card("margem", "bank (river)")))
    }

    @Test
    fun anAsideThatNamesTheAnswerIsFound() {
        assertEquals(
            listOf(CardRule.AsideHoldsAnswer to listOf(0)),
            rules(plain, card("começar (start, not commence)", "start"), card("(ela) Está cansada", "She is tired")),
        )
    }

    @Test
    fun anAsideOnTheBackLeaksOnlyWhenTheDeckReverses() {
        val leak = card("gato", "cat (gato, masculine)")
        assertEquals(emptyList(), rules(plain, leak))
        assertEquals(listOf(CardRule.AsideHoldsAnswer to listOf(0)), rules(reversed, leak))
    }

    @Test
    fun aShortSharedWordIsNotALeak() {
        assertEquals(emptyList(), rules(plain, card("ir (a pé)", "to go on foot, a walk")))
    }

    @Test
    fun alternativesInAnAnswerAreFound() {
        assertEquals(
            listOf(CardRule.Alternatives to listOf(0), CardRule.Alternatives to listOf(1)),
            rules(plain, card("vegetarian", "vegetariano / vegetariana"), card("or", "either/or"), card("speed", "100 km/h")),
        )
        // In an aside it is a note, and notes are never graded.
        assertEquals(emptyList(), rules(plain, card("vegetarian", "vegetariano (m / f)")))
    }

    @Test
    fun anAnswerNothingCanMatchIsFoundOnlyWhenRepliesAreGraded() {
        val dash = card("silence", "—")
        assertEquals(emptyList(), rules(plain, dash))
        assertEquals(listOf(CardRule.NothingToGrade to listOf(0)), rules(graded, dash))
        // A wholly parenthesized answer is still its own text, so it can be typed.
        assertEquals(emptyList(), rules(graded, card("note", "(nota)")))
    }

    @Test
    fun compositionIsCountedPerStretchNotOverTheDeck() {
        val words = List(100) { card("p$it", "word") }
        val phrases = List(100) { card("f$it", "a short phrase (note)") }
        val stretches = CardChecks.composition(words + phrases + listOf(card("x", "")))

        assertEquals(
            listOf(
                CompositionStretch(from = 1, to = 100, words = 100, phrases = 0, untexted = 0),
                CompositionStretch(from = 101, to = 200, words = 0, phrases = 100, untexted = 0),
                CompositionStretch(from = 201, to = 201, words = 0, phrases = 0, untexted = 1),
            ),
            stretches,
        )
    }

    @Test
    fun stretchesWidenPastTheFirstFiveHundred() {
        val stretches = CardChecks.composition(List(1_250) { card("p$it", "word") })

        assertEquals(
            listOf(1 to 100, 101 to 200, 201 to 300, 301 to 400, 401 to 500, 501 to 1_000, 1_001 to 1_250),
            stretches.map { it.from to it.to },
        )
    }
}
