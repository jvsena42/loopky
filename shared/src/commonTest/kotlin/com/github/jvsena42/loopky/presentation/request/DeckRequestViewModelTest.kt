package com.github.jvsena42.loopky.presentation.request

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.domain.model.DeckRequests
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeckRequestViewModelTest {
    private val discovery = FakeDiscoveryRepository()
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start seeds the request with what came up empty`() {
        val vm = DeckRequestViewModel(discovery)

        vm.start("  hiragana ")

        assertEquals("hiragana", vm.state.value.text)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun `a request too short to name a subject cannot be posted`() = runTest(mainDispatcher) {
        val vm = DeckRequestViewModel(discovery)
        vm.start("")
        vm.onTextChange("ab")

        vm.onSubmit("Lead:")
        advanceUntilIdle()

        assertFalse(vm.state.value.canSubmit)
        assertTrue(discovery.deckRequestPosts.isEmpty())
    }

    @Test
    fun `a request over the limit cannot be posted`() {
        val vm = DeckRequestViewModel(discovery)
        vm.onTextChange("x".repeat(DeckRequests.MAX_LENGTH + 1))

        assertFalse(vm.state.value.canSubmit)
    }

    @Test
    fun `submitting posts the lead and the request then shows the confirmation`() = runTest(mainDispatcher) {
        val vm = DeckRequestViewModel(discovery)
        vm.start("Japanese hiragana")

        vm.onSubmit("I'd love a Loopky deck about:")
        assertTrue(vm.state.value.isPosting)
        advanceUntilIdle()

        assertEquals(listOf("I'd love a Loopky deck about:\n\nJapanese hiragana"), discovery.deckRequestPosts)
        assertTrue(vm.state.value.isSent)
        assertFalse(vm.state.value.isPosting)
        assertFalse(vm.state.value.canSubmit)
    }

    @Test
    fun `a failed post keeps the text and says why`() = runTest(mainDispatcher) {
        discovery.deckRequestError = PubkyError("network down")
        val vm = DeckRequestViewModel(discovery)
        vm.start("Japanese hiragana")

        vm.onSubmit("Lead:")
        advanceUntilIdle()

        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.isSent)
        assertEquals("Japanese hiragana", vm.state.value.text)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun `a second tap while posting writes one request`() = runTest(mainDispatcher) {
        val vm = DeckRequestViewModel(discovery)
        vm.start("Japanese hiragana")

        vm.onSubmit("Lead:")
        vm.onSubmit("Lead:")
        advanceUntilIdle()

        assertEquals(1, discovery.deckRequestPosts.size)
    }

    @Test
    fun `reopening after a sent request starts a fresh sheet`() = runTest(mainDispatcher) {
        val vm = DeckRequestViewModel(discovery)
        vm.start("Japanese hiragana")
        vm.onSubmit("Lead:")
        advanceUntilIdle()

        vm.start("biology")

        assertFalse(vm.state.value.isSent)
        assertNull(vm.state.value.error)
        assertEquals("biology", vm.state.value.text)
    }
}
