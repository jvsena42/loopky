package com.github.jvsena42.loopky.presentation.signup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialingCountriesTest {

    @Test
    fun `region codes are unique and two letters`() {
        val regions = DialingCountries.all.map { it.regionCode }
        assertEquals(regions.size, regions.toSet().size)
        assertTrue(regions.all { it.length == 2 && it.all(Char::isUpperCase) })
    }

    /** [DialingCountries.splitInternational] relies on no calling code being a prefix of another. */
    @Test
    fun `calling codes are prefix free`() {
        val codes = DialingCountries.all.map { it.dialCode }.toSet()
        codes.forEach { code ->
            assertTrue(codes.none { it != code && it.startsWith(code) }, "$code prefixes another code")
        }
    }

    @Test
    fun `looks a region up case-insensitively`() {
        assertEquals("55", DialingCountries.forRegion("br")?.dialCode)
        assertNull(DialingCountries.forRegion("ZZ"))
        assertNull(DialingCountries.forRegion(null))
    }

    @Test
    fun `builds the flag from regional indicators`() {
        assertEquals("🇧🇷", DialingCountry("BR", "55").flag)
    }

    @Test
    fun `splits a pasted international number`() {
        val (country, rest) = requireNotNull(DialingCountries.splitInternational("+55 (86) 99800-6407", null))
        assertEquals("BR", country.regionCode)
        assertEquals("86998006407", rest)
    }

    @Test
    fun `keeps the current region when it shares the calling code`() {
        val canada = requireNotNull(DialingCountries.forRegion("CA"))
        assertEquals(canada, DialingCountries.splitInternational("+14155552671", canada)?.first)
        assertEquals("US", DialingCountries.splitInternational("+14155552671", null)?.first?.regionCode)
    }

    @Test
    fun `refuses a number with no known calling code`() {
        assertNull(DialingCountries.splitInternational("+", null))
        assertNull(DialingCountries.splitInternational("+0123", null))
    }

    @Test
    fun `drops a trunk zero except where it belongs to the number`() {
        val uk = requireNotNull(DialingCountries.forRegion("GB"))
        val italy = requireNotNull(DialingCountries.forRegion("IT"))
        assertEquals("7700900123", DialingCountries.nationalPart(uk, "07700 900123"))
        assertEquals("0612345678", DialingCountries.nationalPart(italy, "06 1234 5678"))
    }
}
