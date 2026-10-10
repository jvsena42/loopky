package com.github.jvsena42.loopky.data.anki

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Choosing which two fields to import (#96 finding 4), and the deck tags derived from note tags.
 *
 * The deck shapes here are the ones named in the issue, because they are what "the first two
 * fields" turned into pairs of numeric ids.
 */
class AnkiFieldsTest {

    @Test
    fun idColumnsLoseToSentenceColumns() {
        // 9000 Spanish Sentences: EnglishSentenceID, SpanishSentenceID, EnglishSentence,
        // SpanishSentence. Fields 0 and 1 imported 9,213 cards reading 2528426 -> 2760065.
        val notes = (1..20).map { n ->
            fields("252842$n", "276006$n", "It is what it is.", "Es lo que es.")
        }
        assertEquals(ApkgFieldMapping(2, 3), chooseDefaultFields(notes, fieldCount = 4))
    }

    @Test
    fun aRankColumnLosesButTheWordItRanksDoesNot() {
        // 5000 Spanish Words Sorted by Frequency: Rank, Word, Translation.
        val notes = (1..20).map { n -> fields("$n", "instalación", "installation") }
        assertEquals(ApkgFieldMapping(1, 2), chooseDefaultFields(notes, fieldCount = 3))
    }

    @Test
    fun aThousandsSeparatedRankIsStillARank() {
        // New Spanish Top 5000 writes "4,201".
        val notes = (1..20).map { n -> fields("4,20$n", "salsa", "sauce") }
        assertEquals(ApkgFieldMapping(1, 2), chooseDefaultFields(notes, fieldCount = 3))
    }

    @Test
    fun aPictureFieldCanBeTheBackButNeverTheFront() {
        // Spanish Top 5000's field 1 is Picture; every back used to strip to empty and drop.
        val notes = (1..20).map { picture(text = "el perro", src = "dog.jpg") }
        assertEquals(ApkgFieldMapping(0, 1), chooseDefaultFields(notes, fieldCount = 2))
    }

    @Test
    fun aColumnThatIsUsuallyEmptyIsNotACardSide() {
        val notes = (1..20).map { n ->
            fields("front $n", if (n == 1) "rarely filled" else "", "back $n")
        }
        assertEquals(ApkgFieldMapping(0, 2), chooseDefaultFields(notes, fieldCount = 3))
    }

    @Test
    fun aTwoFieldNoteTypeIsLeftAlone() {
        // The shape "first two fields" was always right for, and the one that needs no scanning.
        val notes = (1..20).map { fields("123", "456") }
        assertEquals(ApkgFieldMapping(0, 1), chooseDefaultFields(notes, fieldCount = 2))
    }

    @Test
    fun shortRealCardFrontsAreNotMistakenForIdentifiers() {
        // A length rule would throw these away along with the ids.
        val notes = (1..20).map { fields("Y qué?", "So what?", "note") }
        assertEquals(ApkgFieldMapping(0, 1), chooseDefaultFields(notes, fieldCount = 3))
    }

    @Test
    fun aDeckWithNoNotesFallsBackToTheFirstTwoFields() {
        assertEquals(ApkgFieldMapping(0, 1), chooseDefaultFields(emptyList(), fieldCount = 5))
    }

    @Test
    fun theMostUsedNoteTagsBecomeTheSuggestedDeckTags() {
        val tags = listOf("biochem enzymes", "biochem", "biochem enzymes", "trivia")
        assertEquals(listOf("biochem", "enzymes", "trivia"), suggestDeckTags(tags))
    }

    @Test
    fun aNestedTagContributesItsLeaf() {
        assertEquals(listOf("enzymes"), suggestDeckTags(listOf("Biochem::Enzymes")))
    }

    @Test
    fun labelsLoopkyWouldRejectAreDroppedRatherThanOffered() {
        val tags = listOf(
            // past the 20-char ceiling
            "a".repeat(21),
            // the app's own bookkeeping
            "loopky-deck",
            "ok",
        )
        assertEquals(listOf("ok"), suggestDeckTags(tags))
    }

    @Test
    fun underscoresBecomeHyphensSoTheLabelHasNoWhitespaceRuleToBreak() {
        assertEquals(listOf("first-year"), suggestDeckTags(listOf("first_year")))
    }

    @Test
    fun anAccentedNoteTagIsSuggestedUnderTheLabelItWouldBeStoredAs() {
        // Counted together too: split by accent, neither spelling might make the cut.
        assertEquals(listOf("bioquimica"), suggestDeckTags(listOf("Bioquímica", "bioquimica")))
    }

    @Test
    fun onlyAHandfulAreSuggested() {
        val tags = (1..20).map { "tag$it" }
        assertEquals(5, suggestDeckTags(tags).size)
    }

    private fun fields(vararg values: String): List<AnkiField> = values.map { AnkiField(it) }

    private fun picture(text: String, src: String): List<AnkiField> =
        listOf(AnkiField(text), AnkiField(text = "", imageSrc = src))
}
