package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeakMatcherTest {

    @Test
    fun exactMatch() {
        assertTrue(SpeakMatcher.match("el zorro", "el zorro").correct)
    }

    @Test
    fun caseInsensitive() {
        assertTrue(SpeakMatcher.match("EL Zorro", "el zorro").correct)
    }

    @Test
    fun ignoresPunctuationAndExtraWhitespace() {
        assertTrue(SpeakMatcher.match("  El, Zorro!  ", "el zorro").correct)
    }

    @Test
    fun ignoresDiacritics() {
        assertTrue(SpeakMatcher.match("el zorro", "él zörró").correct)
        assertTrue(SpeakMatcher.match("buenos dias", "buenos días").correct)
    }

    @Test
    fun mismatchReportsHeardAndExpected() {
        val result = SpeakMatcher.match("el zoro", "el zorro")
        assertFalse(result.correct)
        assertEquals("el zoro", result.heard)
        assertEquals("el zorro", result.expected)
    }

    @Test
    fun ignoresParentheticalAsideOnTheCard() {
        assertTrue(SpeakMatcher.match("hello", "hello (formal)").correct)
        assertTrue(SpeakMatcher.match("hello", "(formal) hello").correct)
        assertTrue(SpeakMatcher.match("hello there", "hello (very formal) there").correct)
        assertTrue(SpeakMatcher.match("konnichiwa", "konnichiwa（丁寧）").correct)
    }

    @Test
    fun ignoresParentheticalInTheTranscript() {
        assertTrue(SpeakMatcher.match("hello (formal)", "hello").correct)
    }

    @Test
    fun stillGradesTheRestOfThePhrase() {
        assertFalse(SpeakMatcher.match("goodbye", "hello (formal)").correct)
    }

    @Test
    fun wholeTextParentheticalStaysAnswerable() {
        assertTrue(SpeakMatcher.match("formal", "(formal)").correct)
    }

    @Test
    fun mismatchStillReportsExpectedAsWritten() {
        assertEquals("hello (formal)", SpeakMatcher.match("goodbye", "hello (formal)").expected)
    }

    @Test
    fun aSpelledNumberAnswersADigitAndTheOtherWayRound() {
        // Which of the two an engine emits is the engine's choice, not the speaker's.
        assertTrue(SpeakMatcher.match("ten", "10", "en-US").correct)
        assertTrue(SpeakMatcher.match("10", "ten", "en-US").correct)
        assertTrue(SpeakMatcher.match("I have 3 cats", "I have three cats", "en-US").correct)
        assertTrue(SpeakMatcher.match("veintidós", "22", "es-ES").correct)
    }

    @Test
    fun theWrongNumberIsStillWrong() {
        assertFalse(SpeakMatcher.match("nine", "10", "en-US").correct)
        assertFalse(SpeakMatcher.match("21", "12", "en-US").correct)
    }

    @Test
    fun numbersAreOnlyFoldedInTheLanguageThatWasDeclared() {
        // "once" is eleven in Spanish and "one time" in English; guessing marks it right.
        assertFalse(SpeakMatcher.match("11", "once", "en-US").correct)
        assertFalse(SpeakMatcher.match("11", "once").correct)
        assertTrue(SpeakMatcher.match("11", "once", "es-ES").correct)
    }

    @Test
    fun aHyphenatedCompoundIsHeardWithOrWithoutTheSpace() {
        // Whether a recognizer emits "self-presentation", "self presentation" or one word is its
        // own choice, exactly like its choice between "ten" and "10".
        assertTrue(SpeakMatcher.match("self presentation", "self-presentation").correct)
        assertTrue(SpeakMatcher.match("self-presentation", "self presentation").correct)
        assertTrue(SpeakMatcher.match("selfpresentation", "self-presentation").correct)
        assertTrue(SpeakMatcher.match("bem-vindo", "bem vindo", "pt-BR").correct)
    }

    @Test
    fun emptyExpectedNeverMatches() {
        assertFalse(SpeakMatcher.match("", "").correct)
        assertFalse(SpeakMatcher.match("anything", "").correct)
    }
}
