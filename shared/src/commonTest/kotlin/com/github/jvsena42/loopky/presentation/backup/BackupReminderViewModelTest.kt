package com.github.jvsena42.loopky.presentation.backup

import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
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
class BackupReminderViewModelTest {

    private val identity = FakeIdentityRepository()
    private val preferences = FakeAppPreferences()
    private val mainDispatcher = StandardTestDispatcher()
    private var day = 100

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = BackupReminderViewModel(
        identityRepository = identity,
        appPreferences = preferences,
        today = { day },
    )

    @Test
    fun `an un-backed-up local key is reminded and the day is recorded`() = runTest(mainDispatcher) {
        identity.custodyFlow.value = KeyCustody.Loopky(pubky = TEST_PUBKY)
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.isVisible)
        assertEquals(100, preferences.backupReminderDayValue)
    }

    @Test
    fun `a reminder already shown today stays away`() = runTest(mainDispatcher) {
        identity.custodyFlow.value = KeyCustody.Loopky(pubky = TEST_PUBKY)
        val first = viewModel()
        advanceUntilIdle()
        first.onDismiss()

        val second = viewModel()
        second.onAppForeground()
        advanceUntilIdle()

        assertFalse(second.state.value.isVisible)
    }

    @Test
    fun `the next day brings it back on foreground`() = runTest(mainDispatcher) {
        identity.custodyFlow.value = KeyCustody.Loopky(pubky = TEST_PUBKY)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onDismiss()

        day = 101
        vm.onAppForeground()
        advanceUntilIdle()

        assertTrue(vm.state.value.isVisible)
        assertEquals(101, preferences.backupReminderDayValue)
    }

    @Test
    fun `any one backup method retires it`() = runTest(mainDispatcher) {
        identity.custodyFlow.value = KeyCustody.Loopky(pubky = TEST_PUBKY)
        val vm = viewModel()
        advanceUntilIdle()

        identity.custodyFlow.value =
            KeyCustody.Loopky(pubky = TEST_PUBKY, backedUpBy = setOf(BackupMethod.EncryptedFile))
        advanceUntilIdle()

        assertFalse(vm.state.value.isVisible)
    }

    @Test
    fun `a Ring-held key is never reminded`() = runTest(mainDispatcher) {
        identity.custodyFlow.value = KeyCustody.External
        val vm = viewModel()
        vm.onAppForeground()
        advanceUntilIdle()

        assertFalse(vm.state.value.isVisible)
        assertEquals(Int.MIN_VALUE, preferences.backupReminderDayValue)
    }

    @Test
    fun `a stranded key belonging to another account is not reminded`() = runTest(mainDispatcher) {
        identity.custodyFlow.value = KeyCustody.Loopky(pubky = "someone-else")
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.isVisible)
        assertEquals(Int.MIN_VALUE, preferences.backupReminderDayValue)
    }
}
