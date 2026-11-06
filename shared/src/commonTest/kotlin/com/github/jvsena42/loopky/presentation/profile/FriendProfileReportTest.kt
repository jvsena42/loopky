package com.github.jvsena42.loopky.presentation.profile

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
class FriendProfileReportTest {

    private val discovery = FakeDiscoveryRepository()
    private val decks = FakeDeckRepository()
    private val stranger = "strangerpk"
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(identity: FakeIdentityRepository = FakeIdentityRepository()) = FriendProfileViewModel(
        targetPubky = stranger,
        identityRepository = identity,
        discoveryRepository = discovery,
        deckRepository = decks,
        pubkyEnvironment = PubkyEnvironment.Production,
    )

    @Test
    fun `report opens the sheet for a signed-in reader`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onReportClick()

        assertTrue(vm.state.value.isReporting)
    }

    @Test
    fun `a guest is asked to sign in instead`() = runTest(mainDispatcher) {
        val vm = viewModel(FakeIdentityRepository(session = null))
        advanceUntilIdle()

        vm.onReportClick()

        assertFalse(vm.state.value.isReporting)
        assertEquals(SignInReason.Report, vm.state.value.signInPrompt)
    }

    @Test
    fun `a sent report that blocked hides the profile and is announced`() = runTest(mainDispatcher) {
        decks.decks["d0"] = testDeck(id = "d0", authorPubky = stranger)
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<FriendProfileEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onReportClick()
        vm.onReportSent(blocked = true)
        advanceUntilIdle()
        job.cancel()

        assertTrue(vm.state.value.isBlocked)
        assertTrue(vm.state.value.decks.isEmpty())
        assertFalse(vm.state.value.isReporting)
        assertEquals(listOf<FriendProfileEffect>(FriendProfileEffect.Reported(blocked = true)), effects)
    }

    @Test
    fun `an account a followee reported is hidden until show anyway`() = runTest(mainDispatcher) {
        discovery.reportedByFollowees.add(stranger)
        val identity = FakeIdentityRepository().apply {
            profiles[stranger] = PubkyIdentity(stranger, "Troll", null, null)
        }
        decks.decks["d0"] = testDeck(id = "d0", authorPubky = stranger)
        val vm = viewModel(identity)
        advanceUntilIdle()

        assertTrue(vm.state.value.isHiddenByReport)
        assertEquals(null, vm.state.value.identity.displayName)
        assertFalse(vm.state.value.canReport)

        vm.onShowAnyway()
        advanceUntilIdle()

        assertFalse(vm.state.value.isHiddenByReport)
        assertEquals("Troll", vm.state.value.identity.displayName)
    }
}
