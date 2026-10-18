package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageTagsTest {

    @Test
    fun theRegionPicksAVoiceNeverALabel() {
        // es-ES and es-MX are both just Spanish: labelling them apart would halve a search for
        // Spanish decks, and a deck with both sides in Spanish carries one label, not two.
        assertEquals(listOf(LanguageTags.UMBRELLA, Tag("spanish")), LanguageTags.forPair("es-ES", "es-MX"))
        assertEquals(
            listOf(LanguageTags.UMBRELLA, Tag("english"), Tag("spanish")),
            LanguageTags.forPair("en-US", "es-ES"),
        )
    }

    @Test
    fun aDeckWithNoDeclaredPairCarriesNoLanguageLabel() {
        // Not even the umbrella: a deck with no declared pair is not a language deck.
        assertEquals(emptyList(), LanguageTags.forPair(null, null))
        assertEquals(listOf(LanguageTags.UMBRELLA, Tag("english")), LanguageTags.forPair("en", null))
        assertEquals(emptyList(), LanguageTags.forPair("  ", null))
    }

    @Test
    fun anUnknownSubtagLabelsUnderItselfRatherThanGoingUntagged() {
        // Findable under something is better than findable under nothing.
        assertEquals(listOf(LanguageTags.UMBRELLA, Tag("xh")), LanguageTags.forPair("xh-ZA", null))
    }

    @Test
    fun retagSwapsTheOldPairsLabelsAndKeepsEverythingElseInPlace() {
        assertEquals(
            listOf("verbs", "english", "language", "french"),
            LanguageTags.retag(
                listOf("verbs", "spanish", "english"),
                "en-US",
                "es-ES",
                "en-US",
                "fr-FR",
            ),
        )
    }

    @Test
    fun retagLeavesALabelThatBothPairsShare() {
        // "english" is in both, so it must not be dropped and re-appended — the author's tag order
        // is theirs, and reordering on every pick would look like the list rewriting itself.
        assertEquals(
            listOf("language", "english", "verbs", "french"),
            LanguageTags.retag(
                listOf("language", "english", "spanish", "verbs"),
                "en-US",
                "es-ES",
                "en-US",
                "fr-FR",
            ),
        )
    }

    @Test
    fun retagAddsTheFirstPickToADeckThatHadNoLanguage() {
        assertEquals(
            listOf("verbs", "language", "spanish"),
            LanguageTags.retag(listOf("verbs"), null, null, "es-ES", null),
        )
    }

    @Test
    fun anUnderscoreSeparatesTheRegionToo() {
        // pt_BR is what Java and POSIX locales print (#534).
        assertEquals(
            listOf(LanguageTags.UMBRELLA, Tag("portuguese"), Tag("english")),
            LanguageTags.forPair("pt_BR", "en_US"),
        )
    }

    @Test
    fun aCodeThatIsNoLanguageSubtagContributesNoLabel() {
        assertEquals(emptyList(), LanguageTags.forPair("Not A Lang!!", null))
        assertEquals(listOf(LanguageTags.UMBRELLA, Tag("russian")), LanguageTags.forPair("Not A Lang!!", "ru-RU"))
    }

    @Test
    fun retagShedsTheLabelAPreFoldCodeLeftBehind() {
        // Before #534 pt_BR stored the label pt-br, which retag then never recognised as derived.
        assertEquals(
            listOf("language", "english", "portuguese"),
            LanguageTags.retag(listOf("language", "pt-br", "english"), "pt_BR", "en-US", "pt-BR", "en-US"),
        )
        assertEquals(
            listOf("language", "russian"),
            LanguageTags.retag(listOf("language", "not-a-lang!!"), "Not A Lang!!", null, "ru-RU", null),
        )
    }
}
