package com.github.jvsena42.loopky.presentation.signup

import com.github.jvsena42.loopky.testing.FakeSignupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneVerificationViewModelTest {

    private val signupRepo = FakeSignupRepository()
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(region: String? = "GB") =
        PhoneVerificationViewModel(signupRepository = signupRepo, regionCode = region)

    @Test
    fun `starts on the device region`() {
        assertEquals("GB", viewModel().state.value.country.regionCode)
    }

    @Test
    fun `falls back when the device region has no calling code`() {
        assertEquals(DialingCountries.fallback, viewModel(region = null).state.value.country)
        assertEquals(DialingCountries.fallback, viewModel(region = "ZZ").state.value.country)
    }

    @Test
    fun `sends the national number under the selected calling code`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onPhoneNumberChange("07700 900123")
        assertTrue(vm.state.value.canSendCode)
        assertEquals("+44 7700900123", vm.state.value.displayNumber)

        vm.onSendCodeClick()
        advanceUntilIdle()

        assertEquals(listOf("+447700900123"), signupRepo.sentSmsNumbers)
    }

    @Test
    fun `picking another country keeps the typed number`() {
        val vm = viewModel()
        vm.onPhoneNumberChange("86998006407")
        vm.onCountrySelected(requireNotNull(DialingCountries.forRegion("BR")))

        assertEquals("86998006407", vm.state.value.phoneNumber)
        assertEquals("+5586998006407", vm.state.value.internationalNumber)
    }

    /** A paste from contacts or an autofill carries its own `+` — it must not be prefixed again. */
    @Test
    fun `a pasted international number moves the picker`() {
        val vm = viewModel()
        vm.onPhoneNumberChange("+55 (86) 99800-6407")

        assertEquals("BR", vm.state.value.country.regionCode)
        assertEquals("86998006407", vm.state.value.phoneNumber)
        assertEquals("+5586998006407", vm.state.value.internationalNumber)
    }

    @Test
    fun `a partial number cannot be sent`() {
        val vm = viewModel()
        vm.onPhoneNumberChange("0770")
        assertFalse(vm.state.value.canSendCode)
    }

    @Test
    fun `a resumed number is split back into country and national part`() = runTest(mainDispatcher) {
        signupRepo.resumableSmsPhoneNumber = "+5586998006407"
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(PhoneVerificationPhase.CodeEntry, vm.state.value.phase)
        assertEquals("BR", vm.state.value.country.regionCode)
        assertEquals("+5586998006407", vm.state.value.internationalNumber)
    }
}
