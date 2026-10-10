package com.github.jvsena42.loopky.domain.model

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `TagLabels` carries its own fold table because `commonMain` has no Unicode normalizer. This is
 * what keeps that table honest: every lowercase character in the BMP is folded both ways, so a
 * missing row, a wrong base letter and a non-Latin letter that got folded all fail here.
 */
class TagLabelsJvmTest {

    @Test
    fun `the fold table agrees with NFD for every Latin letter and touches nothing else`() {
        val disagreements = (FIRST_NON_ASCII..Char.MAX_VALUE.code)
            .map { it.toChar() }
            .filterNot { it.isSurrogate() || it.isWhitespace() || it in COMBINING_MARKS }
            .map { it.toString() }
            .filter { it.lowercase() == it }
            .filter { TagLabels.fold(it) != expectedFold(it) }

        assertEquals(emptyList(), disagreements)
    }

    /** NFD with the marks dropped when what is left is a Latin letter; the character itself otherwise. */
    private fun expectedFold(char: String): String {
        val decomposed = Normalizer.normalize(char, Normalizer.Form.NFD)
        val base = decomposed.first()
        val marksOnly = decomposed.drop(1).all { it in COMBINING_MARKS }
        return when {
            base in UNDECOMPOSABLE && marksOnly -> UNDECOMPOSABLE.getValue(base)
            base in 'a'..'z' && marksOnly -> base.toString()
            else -> char
        }
    }

    private companion object {
        const val FIRST_NON_ASCII = 0x80
        val COMBINING_MARKS = '\u0300'..'\u036F'
        val UNDECOMPOSABLE = mapOf(
            'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'đ' to "d", 'ł' to "l", 'ı' to "i",
        )
    }
}
