package com.github.jvsena42.loopky.presentation.settings

import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BlockedUsersViewModelTest {

    private val discovery = FakeDiscoveryRepository()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `lists the blocked accounts with their names`() = runTest {
        discovery.blocks += setOf("adapk", "barepk")
        discovery.profiles = mapOf("adapk" to PubkyIdentity("adapk", "Ada", null, null))

        val vm = BlockedUsersViewModel(discovery)
        advanceUntilIdle()

        assertEquals(listOf("adapk", "barepk"), vm.state.value.people.map { it.pubky })
        assertEquals("Ada", vm.state.value.people.first().displayName)
    }

    @Test
    fun `an empty list is empty and not loading`() = runTest {
        val vm = BlockedUsersViewModel(discovery)
        advanceUntilIdle()

        assertTrue(vm.state.value.isEmpty)
    }

    @Test
    fun `unblock removes the row`() = runTest {
        discovery.blocks += setOf("adapk", "barepk")
        val vm = BlockedUsersViewModel(discovery)
        advanceUntilIdle()

        vm.onUnblock("adapk")
        advanceUntilIdle()

        assertEquals(listOf("barepk"), vm.state.value.people.map { it.pubky })
        assertEquals(setOf("barepk"), discovery.blocks)
    }

    @Test
    fun `a failed unblock keeps the row and reports it`() = runTest {
        discovery.blocks += "adapk"
        val vm = BlockedUsersViewModel(discovery)
        advanceUntilIdle()
        discovery.blockError = IllegalStateException("down")
        val effects = mutableListOf<BlockedUsersEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onUnblock("adapk")
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("adapk"), vm.state.value.people.map { it.pubky })
        assertTrue(vm.state.value.unblocking.isEmpty())
        assertIs<BlockedUsersEffect.ShowError>(effects.single())
    }
}
