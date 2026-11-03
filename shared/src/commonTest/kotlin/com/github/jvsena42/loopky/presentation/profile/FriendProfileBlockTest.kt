package com.github.jvsena42.loopky.presentation.profile

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.fakeSession
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FriendProfileBlockTest {

    private val identity = FakeIdentityRepository()
    private val discovery = FakeDiscoveryRepository()
    private val decks = FakeDeckRepository()
    private val stranger = "strangerpk"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(target: String = stranger) = FriendProfileViewModel(
        targetPubky = target,
        identityRepository = identity,
        discoveryRepository = discovery,
        deckRepository = decks,
        pubkyEnvironment = PubkyEnvironment.Production,
    )

    @Test
    fun `block asks first and writes nothing until confirmed`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onBlockClick()
        advanceUntilIdle()

        assertTrue(vm.state.value.isConfirmingBlock)
        assertTrue(discovery.blocks.isEmpty())

        vm.onBlockConfirmation(confirmed = false)
        assertFalse(vm.state.value.isConfirmingBlock)
        assertTrue(discovery.blocks.isEmpty())
    }

    @Test
    fun `a confirmed block blocks and unfollows`() = runTest {
        discovery.follows.add(stranger)
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<FriendProfileEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onBlockClick()
        vm.onBlockConfirmation(confirmed = true)
        advanceUntilIdle()
        job.cancel()

        assertEquals(setOf(stranger), discovery.blocks)
        assertTrue(vm.state.value.isBlocked)
        assertFalse(vm.state.value.isFollowing)
        assertTrue(FriendProfileEffect.Blocked in effects)
    }

    @Test
    fun `unblock needs no confirmation`() = runTest {
        discovery.blocks.add(stranger)
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.isBlocked)

        vm.onBlockClick()
        advanceUntilIdle()

        assertFalse(vm.state.value.isConfirmingBlock)
        assertFalse(vm.state.value.isBlocked)
        assertTrue(discovery.blocks.isEmpty())
    }

    @Test
    fun `a failed block keeps the profile unblocked and says why`() = runTest {
        discovery.blockError = IllegalStateException("homeserver down")
        val vm = viewModel()
        advanceUntilIdle()

        vm.onBlockClick()
        vm.onBlockConfirmation(confirmed = true)
        advanceUntilIdle()

        assertFalse(vm.state.value.isBlocked)
        assertNotNull(vm.state.value.errorReason)
    }

    @Test
    fun `a guest cannot block and neither can you on your own profile`() = runTest {
        identity.session = null
        val guest = viewModel()
        advanceUntilIdle()
        guest.onBlockClick()
        assertFalse(guest.state.value.isConfirmingBlock)

        identity.session = fakeSession()
        val self = viewModel(target = TEST_PUBKY)
        advanceUntilIdle()
        self.onBlockClick()
        assertFalse(self.state.value.isConfirmingBlock)
    }

    @Test
    fun `a blocked profile cannot be followed`() = runTest {
        discovery.blocks.add(stranger)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onToggleFollow()
        advanceUntilIdle()

        assertTrue(discovery.follows.isEmpty())
    }

    @Test
    fun `a deck they follow by someone you blocked is left out of their grid`() = runTest {
        discovery.blocks.add("trollpk")
        decks.followedDecksByOwner[stranger] = listOf(
            testDeck(id = "bad", authorPubky = "trollpk"),
            testDeck(id = "good", authorPubky = "friendpk"),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("good"), vm.state.value.decks.map { it.id })
    }
}
