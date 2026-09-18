package com.github.jvsena42.loopky.presentation.signup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneNumberInputTest {

    @Test
    fun `accepts a plain E164 number`() {
        assertTrue(PhoneNumberInput.isValid("+5586998006407"))
        assertTrue(PhoneNumberInput.isValid("+14155552671"))
        assertTrue(PhoneNumberInput.isValid("+3161234567"))
    }

    /** A paste from a contacts app is the same number, so punctuation is fixed rather than judged. */
    @Test
    fun `accepts the separators a contacts app pastes`() {
        assertTrue(PhoneNumberInput.isValid("+55 (86) 99800-6407"))
        assertTrue(PhoneNumberInput.isValid("+1 415.555.2671"))
        assertEquals("+5586998006407", PhoneNumberInput.normalize("+55 (86) 99800-6407"))
    }

    /** The whole point: this is what used to reach Homegate and spend an attempt. */
    @Test
    fun `rejects a number with no country code`() {
        assertFalse(PhoneNumberInput.isValid("5586998006407"))
        assertFalse(PhoneNumberInput.isValid("86998006407"))
    }

    @Test
    fun `rejects a blank or partial entry`() {
        assertFalse(PhoneNumberInput.isValid(""))
        assertFalse(PhoneNumberInput.isValid("   "))
        assertFalse(PhoneNumberInput.isValid("+"))
        assertFalse(PhoneNumberInput.isValid("+55"))
    }

    @Test
    fun `rejects a country code starting at zero`() {
        assertFalse(PhoneNumberInput.isValid("+05586998006407"))
    }

    @Test
    fun `rejects letters and a second plus`() {
        assertFalse(PhoneNumberInput.isValid("+55abc998006407"))
        assertFalse(PhoneNumberInput.isValid("++5586998006407"))
    }

    @Test
    fun `rejects a number past the E164 ceiling`() {
        assertFalse(PhoneNumberInput.isValid("+1234567890123456"))
    }
}
