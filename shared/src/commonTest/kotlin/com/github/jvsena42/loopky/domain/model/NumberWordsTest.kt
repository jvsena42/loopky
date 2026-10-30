package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class NumberWordsTest {

    @Test
    fun foldsPlainNumberWords() {
        assertEquals("10", NumberWords.fold("ten", "en-US"))
        assertEquals("0", NumberWords.fold("zero", "en-US"))
        assertEquals("10", NumberWords.fold("diez", "es-ES"))
        assertEquals("7", NumberWords.fold("sette", "it-IT"))
    }

    @Test
    fun leavesEverythingAroundTheNumberAlone() {
        assertEquals("I have 3 cats.", NumberWords.fold("I have three cats.", "en-US"))
        assertEquals("  8 ", NumberWords.fold("  eight ", "en-GB"))
    }

    @Test
    fun leavesDigitsAsTheyAre() {
        assertEquals("I have 3 cats", NumberWords.fold("I have 3 cats", "en-US"))
    }

    @Test
    fun foldsCompoundsJoinedBySpacesHyphensOrTheLanguagesFiller() {
        assertEquals("21", NumberWords.fold("twenty-one", "en-US"))
        assertEquals("21", NumberWords.fold("twenty one", "en-US"))
        assertEquals("105", NumberWords.fold("one hundred and five", "en-US"))
        assertEquals("31", NumberWords.fold("treinta y uno", "es-ES"))
    }

    @Test
    fun scalesMultiplyWhatCameBeforeThem() {
        assertEquals("200", NumberWords.fold("two hundred", "en-US"))
        assertEquals("2500", NumberWords.fold("two thousand five hundred", "en-US"))
        assertEquals("100000", NumberWords.fold("one hundred thousand", "en-US"))
        assertEquals("1000", NumberWords.fold("mil", "es-ES"))
        assertEquals("5000", NumberWords.fold("cinco mil", "es-ES"))
    }

    @Test
    fun frenchCountsInTwenties() {
        // The rule that separates "quatre-vingt-dix" (90) from a naive 4 + 20 + 10.
        assertEquals("90", NumberWords.fold("quatre-vingt-dix", "fr-FR"))
        assertEquals("80", NumberWords.fold("quatre-vingts", "fr-FR"))
        assertEquals("70", NumberWords.fold("soixante-dix", "fr-FR"))
        assertEquals("21", NumberWords.fold("vingt et un", "fr-FR"))
        // Swiss and Belgian forms are ordinary words, not vigesimal compounds.
        assertEquals("90", NumberWords.fold("nonante", "fr-CH"))
    }

    @Test
    fun accentsAndCaseDoNotHideANumber() {
        assertEquals("22", NumberWords.fold("veintidós", "es-MX"))
        assertEquals("3", NumberWords.fold("Três", "pt-BR"))
        assertEquals("5", NumberWords.fold("FÜNF", "de-DE"))
    }

    @Test
    fun aFillerOnlyCountsWhenItSitsInsideANumber() {
        assertEquals("bread and butter", NumberWords.fold("bread and butter", "en-US"))
        assertEquals("2 and butter", NumberWords.fold("two and butter", "en-US"))
        assertEquals("pan y agua", NumberWords.fold("pan y agua", "es-ES"))
    }

    @Test
    fun punctuationBetweenNumbersEndsTheRun() {
        // "one, two" is two numbers listed, not the number 12.
        assertEquals("1, 2", NumberWords.fold("one, two", "en-US"))
    }

    @Test
    fun hyphensInOrdinaryWordsAreNotTouched() {
        // The run only spans number words, so a hyphen elsewhere is left exactly as written —
        // this must not become a general word splitter.
        assertEquals("e-mail", NumberWords.fold("e-mail", "en-US"))
        assertEquals("well-known", NumberWords.fold("well-known", "en-US"))
    }

    @Test
    fun anUndeclaredOrUnsupportedLanguageFoldsNothing() {
        // Never guessed: "once" is Spanish 11 and English "one time"; "elf" is German 11 and an
        // English creature. Folding those outside their language marks wrong answers right.
        assertEquals("ten", NumberWords.fold("ten", null))
        assertEquals("ten", NumberWords.fold("ten", "ja-JP"))
        assertEquals("once", NumberWords.fold("once", "en-US"))
        assertEquals("elf", NumberWords.fold("elf", "en-US"))
        assertEquals("11", NumberWords.fold("once", "es-ES"))
        assertEquals("11", NumberWords.fold("elf", "de-DE"))
    }

    @Test
    fun formsTheListDoesNotCarryAreLeftAlone() {
        // A miss, never a wrong fold: the deck simply keeps grading them as text.
        assertEquals("ventotto", NumberWords.fold("ventotto", "it-IT"))
        assertEquals("einundzwanzig", NumberWords.fold("einundzwanzig", "de-DE"))
    }

    @Test
    fun hanNumeralsFoldInJapaneseAndChinese() {
        assertEquals("3時", NumberWords.fold("三時", "ja-JP"))
        assertEquals("10", NumberWords.fold("十", "ja-JP"))
        assertEquals("25歳", NumberWords.fold("二十五歳", "ja-JP"))
        assertEquals("35000円", NumberWords.fold("三万五千円", "ja-JP"))
        assertEquals("105", NumberWords.fold("一百零五", "zh-CN"))
        assertEquals("2个", NumberWords.fold("两个", "zh-CN"))
        assertEquals("2026年", NumberWords.fold("二〇二六年", "zh-CN"))
    }

    @Test
    fun hanNumeralsNeedTheDeclaredLanguage() {
        assertEquals("三時", NumberWords.fold("三時", null))
        assertEquals("三時", NumberWords.fold("三時", "ko-KR"))
    }

    @Test
    fun koreanFoldsBothCountingSystems() {
        assertEquals("3 시", NumberWords.fold("세 시", "ko-KR"))
        assertEquals("30 분", NumberWords.fold("삼십 분", "ko-KR"))
        assertEquals("35", NumberWords.fold("삼십오", "ko-KR"))
        assertEquals("11", NumberWords.fold("열하나", "ko-KR"))
        assertEquals("21 살", NumberWords.fold("스물한 살", "ko-KR"))
    }

    @Test
    fun koreanWordsMadeOfDigitSyllablesAloneAreNotNumbers() {
        assertEquals("사이", NumberWords.fold("사이", "ko-KR"))
        assertEquals("오이", NumberWords.fold("오이", "ko-KR"))
    }

    @Test
    fun foldsEveryNumberInASentence() {
        assertEquals("2 dogs and 3 cats", NumberWords.fold("two dogs and three cats", "en-US"))
    }
}
