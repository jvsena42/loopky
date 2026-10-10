package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TagLabelsTest {

    @Test
    fun everySpellingOfOneTopicFoldsToOneLabel() {
        val spellings = listOf("Café", " café ", "CAFE", "cafe\u0301", "cafe")
        assertEquals(setOf("cafe"), spellings.map(TagLabels::fold).toSet())
    }

    @Test
    fun diacriticsAreStrippedAcrossTheLanguagesLoopkyShipsIn() {
        assertEquals("bioquimica", TagLabels.fold("bioquímica"))
        assertEquals("francais", TagLabels.fold("Français"))
        assertEquals("uber", TagLabels.fold("Über"))
        assertEquals("tieng-viet", TagLabels.fold("Tiếng Việt"))
        assertEquals("da-nang", TagLabels.fold("Đà Nẵng"))
    }

    @Test
    fun lettersWithNoDecompositionFoldToTheirPlainSpelling() {
        // NFD leaves these alone, so a normalization form alone would keep `straße` and `strasse` apart.
        assertEquals("strasse", TagLabels.fold("Straße"))
        assertEquals("smorrebrod", TagLabels.fold("smørrebrød"))
        assertEquals("oeuvre", TagLabels.fold("œuvre"))
        assertEquals("lodz", TagLabels.fold("Łódź"))
    }

    @Test
    fun wordsAreJoinedByOneHyphenHoweverTheyWereSeparated() {
        val spellings = listOf("foo bar", "foo_bar", "foo-bar", "foo   bar", "foo _- bar", "\tfoo\nbar ", "_foo-bar-")
        assertEquals(setOf("foo-bar"), spellings.map(TagLabels::fold).toSet())
        // No separator between them, so no way to know there were two words.
        assertEquals("foobar", TagLabels.fold("FooBar"))
    }

    @Test
    fun otherPunctuationIsPartOfTheLabel() {
        assertEquals("c++", TagLabels.fold("C++"))
        assertEquals("c#", TagLabels.fold("C#"))
        assertEquals("node.js", TagLabels.fold("Node.js"))
    }

    @Test
    fun scriptsWithNoLatinBasePassThroughUntouched() {
        // Stripping every combining mark would rewrite each of these into a different word.
        listOf("日本語", "한국어", "русский", "йога", "ёж", "العَرَبِيَّة", "हिन्दी", "が", "ελληνικά").forEach {
            assertEquals(it, TagLabels.fold(it))
        }
    }

    @Test
    fun emojiPassThroughWholeWhateverTheyAreBuiltFrom() {
        val emoji = listOf(
            "🎉", // one surrogate pair
            "👍🏽", // skin-tone modifier
            "👨‍👩‍👧‍👦", // joined by zero-width joiners
            "🇧🇷", // a pair of regional indicators
            "❤️", // variation selector 16
            "1️⃣", // keycap: digit, selector, combining enclosing keycap
            "🏳️‍🌈",
        )
        emoji.forEach {
            assertEquals(it, TagLabels.fold(it), "fold changed $it")
            assertEquals(it, TagLabels.normalize(it), "normalize changed or refused $it")
        }
    }

    @Test
    fun theLengthLimitCountsAnEmojiOnce() {
        // pubky-app-specs counts code points and accepts twenty emoji; counting UTF-16 units
        // would stop at ten.
        assertEquals("🔥".repeat(20), TagLabels.normalize("🔥".repeat(20)))
        assertNull(TagLabels.normalize("🔥".repeat(21)))
        assertEquals(20, TagLabels.lengthOf("🔥".repeat(20)))
        assertEquals(7, TagLabels.lengthOf("👨‍👩‍👧‍👦"))
    }

    @Test
    fun emojiBesideTextKeepTheTextFolding() {
        assertEquals("cafe-☕", TagLabels.fold("Café ☕"))
        assertEquals("🎉festa", TagLabels.fold("🎉Festa"))
        assertEquals("a️⃣", TagLabels.fold("A️⃣"))
        // A Latin letter straight after an emoji still loses its accent, and nothing else moves.
        assertEquals("🇧🇷-sao-paulo", TagLabels.fold("🇧🇷 São_Paulo"))
    }

    @Test
    fun aCombiningMarkIsOnlyDroppedAfterALatinLetter() {
        // Decomposed `й`: the breve belongs to a Cyrillic letter and stays.
        assertEquals("и\u0306", TagLabels.fold("и\u0306"))
        assertEquals("nino", TagLabels.fold("nin\u0303o"))
    }

    @Test
    fun normalizeRefusesWhatCannotBeStored() {
        assertNull(TagLabels.normalize("   "))
        assertNull(TagLabels.normalize("_-_"))
        assertNull(TagLabels.normalize("loopky-deck"))
        // Reserved only once folded — the check has to come after.
        assertNull(TagLabels.normalize("Loopky Deck"))
        assertNull(TagLabels.normalize("loopky_followed"))
    }

    @Test
    fun theLengthLimitIsMeasuredAfterFolding() {
        // 21 characters as typed, 20 once the doubled separator collapses.
        assertEquals("a".repeat(10) + "-" + "b".repeat(9), TagLabels.normalize("a".repeat(10) + "  " + "b".repeat(9)))
        // 20 as typed, 21 once `ß` becomes `ss`.
        assertNull(TagLabels.normalize("a".repeat(19) + "ß"))
        assertNull(TagLabels.normalize("a".repeat(21)))
    }

    @Test
    fun normalizeAllDropsDuplicatesThatOnlyAppearAfterFolding() {
        assertEquals(
            listOf("cafe", "foo-bar"),
            TagLabels.normalizeAll(listOf("café", "Cafe", "", "foo bar", "loopky-user", "foo_bar")),
        )
        assertEquals(listOf(Tag("cafe")), listOf(Tag("Café"), Tag("cafe")).normalized())
    }

    @Test
    fun theLanguageLabelsAreAlreadyCanonical() {
        val labels = LanguageTags.forPair("pt-BR", "zh-Hans").map { it.value }
        assertEquals(labels, TagLabels.normalizeAll(labels))
    }
}
