package com.github.jvsena42.loopky.presentation.report

import com.github.jvsena42.loopky.domain.model.ReportIssue
import com.github.jvsena42.loopky.domain.model.ReportLimits
import com.github.jvsena42.loopky.domain.model.ReportSubject
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
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
class ReportViewModelTest {

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
    fun `nothing is sent until an issue is picked`() = runTest(mainDispatcher) {
        val vm = ReportViewModel("trollpk", deck = null, discoveryRepository = discovery)

        vm.onSend()
        advanceUntilIdle()

        assertFalse(vm.state.value.canSend)
        assertTrue(discovery.reports.isEmpty())
    }

    @Test
    fun `a profile report is sent with its issue and details and announced`() = runTest(mainDispatcher) {
        val vm = ReportViewModel("trollpk", deck = null, discoveryRepository = discovery)
        val effects = mutableListOf<ReportEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onIssueSelected(ReportIssue.Harassment)
        vm.onDetailsChange("insults in every deck")
        vm.onSend()
        advanceUntilIdle()
        job.cancel()

        assertEquals(
            FakeDiscoveryRepository.SentReport(ReportSubject.Profile("trollpk"), ReportIssue.Harassment, "insults in every deck"),
            discovery.reports.single(),
        )
        assertEquals(listOf<ReportEffect>(ReportEffect.Sent(blocked = true)), effects)
    }

    @Test
    fun `a deck report reports the deck`() = runTest(mainDispatcher) {
        val deck = testDeck(id = "d1", authorPubky = "trollpk")
        val vm = ReportViewModel("trollpk", deck = deck, discoveryRepository = discovery)

        vm.onIssueSelected(ReportIssue.Copyright)
        vm.onSend()
        advanceUntilIdle()

        assertTrue(vm.state.value.isDeck)
        assertEquals(ReportSubject.OfDeck(deck), discovery.reports.single().subject)
    }

    @Test
    fun `a block that fails after the report is reported as not blocked`() = runTest(mainDispatcher) {
        discovery.blockError = IllegalStateException("homeserver down")
        val vm = ReportViewModel("trollpk", deck = null, discoveryRepository = discovery)
        val effects = mutableListOf<ReportEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onIssueSelected(ReportIssue.Violence)
        vm.onSend()
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf<ReportEffect>(ReportEffect.Sent(blocked = false)), effects)
    }

    @Test
    fun `a report that does not arrive keeps the sheet and says why`() = runTest(mainDispatcher) {
        discovery.reportError = IllegalStateException("offline")
        val vm = ReportViewModel("trollpk", deck = null, discoveryRepository = discovery)

        vm.onIssueSelected(ReportIssue.Violence)
        vm.onSend()
        advanceUntilIdle()

        assertNotNull(vm.state.value.errorReason)
        assertFalse(vm.state.value.isSending)
        assertTrue(vm.state.value.canSend)
    }

    @Test
    fun `details are capped`() {
        val vm = ReportViewModel("trollpk", deck = null, discoveryRepository = discovery)

        vm.onDetailsChange("x".repeat(ReportLimits.DETAILS_MAX_LENGTH + 50))

        assertEquals(ReportLimits.DETAILS_MAX_LENGTH, vm.state.value.details.length)
    }

    @Test
    fun `a sheet closed after a failed send reopens empty`() = runTest(mainDispatcher) {
        discovery.reportError = IllegalStateException("offline")
        val vm = ReportViewModel("trollpk", deck = null, discoveryRepository = discovery)
        vm.onIssueSelected(ReportIssue.Harassment)
        vm.onDetailsChange("x")
        vm.onSend()
        advanceUntilIdle()
        assertNotNull(vm.state.value.errorReason)

        vm.onDismiss()

        assertEquals(ReportUiState(isDeck = false), vm.state.value)
    }

    @Test
    fun `a sent report leaves nothing selected for the next one`() = runTest(mainDispatcher) {
        val vm = ReportViewModel("trollpk", deck = null, discoveryRepository = discovery)
        vm.onIssueSelected(ReportIssue.Harassment)
        vm.onSend()
        advanceUntilIdle()

        assertFalse(vm.state.value.canSend)
        assertEquals(null, vm.state.value.issue)
    }
}
