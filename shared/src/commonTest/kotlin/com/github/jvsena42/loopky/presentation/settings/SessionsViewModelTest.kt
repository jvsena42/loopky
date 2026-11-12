package com.github.jvsena42.loopky.presentation.settings

import com.github.jvsena42.loopky.domain.model.SignInRefusal
import com.github.jvsena42.loopky.testing.FakeSignerRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val signer = FakeSignerRepository()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `lists the approved apps`() = runTest(dispatcher) {
        val vm = SessionsViewModel(signer, SignInInbox())
        advanceUntilIdle()

        assertEquals(listOf("g1", "me"), vm.state.value.sessions.map { it.grantId })
        assertTrue(vm.state.value.canApprove)
    }

    @Test
    fun `lists nothing when Pubky Ring holds the key`() = runTest(dispatcher) {
        signer.canApproveFlow.value = false
        val vm = SessionsViewModel(signer, SignInInbox())
        advanceUntilIdle()

        assertEquals(false, vm.state.value.canApprove)
        assertEquals(0, signer.listCalls)
    }

    @Test
    fun `a link is shown for review and approved only on the tap`() = runTest(dispatcher) {
        val vm = SessionsViewModel(signer, SignInInbox())
        advanceUntilIdle()

        vm.onSignInLink(LINK)
        assertEquals(ApprovalPhase.Reviewing, vm.state.value.approval?.phase)
        assertEquals(0, signer.approved.size)

        vm.onApprove(LINK)
        advanceUntilIdle()
        assertEquals(ApprovalPhase.Approved, vm.state.value.approval?.phase)
        assertEquals(1, signer.approved.size)

        vm.onDismissApproval()
        advanceUntilIdle()
        assertNull(vm.state.value.approval)
        assertEquals(2, signer.listCalls)
    }

    @Test
    fun `a link pasted into Search arrives for review`() = runTest(dispatcher) {
        val inbox = SignInInbox().apply { deliver(LINK) }
        val vm = SessionsViewModel(signer, inbox)
        advanceUntilIdle()

        assertEquals(LINK, vm.state.value.approval?.shownLink)
        assertNull(inbox.take())
    }

    @Test
    fun `a second link does not replace the one on screen`() = runTest(dispatcher) {
        val vm = SessionsViewModel(signer, SignInInbox())
        advanceUntilIdle()

        vm.onSignInLink(LINK)
        vm.onSignInLink(OTHER_LINK)
        assertEquals(LINK, vm.state.value.approval?.shownLink)

        vm.onApprove(OTHER_LINK)
        advanceUntilIdle()
        assertEquals(0, signer.approved.size)

        vm.onApprove(LINK)
        advanceUntilIdle()
        assertEquals(listOf(LINK), signer.approved.map { it.link })
    }

    @Test
    fun `a refused link offers no approval`() = runTest(dispatcher) {
        signer.refusal = SignInRefusal.WholeAccount
        val vm = SessionsViewModel(signer, SignInInbox())
        advanceUntilIdle()

        vm.onSignInLink(LINK)
        vm.onApprove(LINK)
        advanceUntilIdle()

        assertEquals(ApprovalPhase.Refused, vm.state.value.approval?.phase)
        assertEquals(SignInRefusal.WholeAccount, vm.state.value.approval?.refusal)
        assertEquals(0, signer.approved.size)
    }

    @Test
    fun `a failed approval can be tried again`() = runTest(dispatcher) {
        signer.approveResult = Result.failure(IllegalStateException("relay gone"))
        val vm = SessionsViewModel(signer, SignInInbox())
        advanceUntilIdle()

        vm.onSignInLink(LINK)
        vm.onApprove(LINK)
        advanceUntilIdle()
        assertEquals(ApprovalPhase.Failed, vm.state.value.approval?.phase)

        signer.approveResult = Result.success(Unit)
        vm.onApprove(LINK)
        advanceUntilIdle()
        assertEquals(ApprovalPhase.Approved, vm.state.value.approval?.phase)
    }

    @Test
    fun `revoking removes the row and never touches this device's own`() = runTest(dispatcher) {
        val vm = SessionsViewModel(signer, SignInInbox())
        advanceUntilIdle()

        vm.onRevoke("me")
        vm.onRevoke("g1")
        advanceUntilIdle()

        assertEquals(listOf("g1"), signer.revoked)
        assertEquals(listOf("me"), vm.state.value.sessions.map { it.grantId })
    }

    private companion object {
        const val LINK = "pubkyauth://signin_grant?caps=/pub/loopky/:rw"
        const val OTHER_LINK = "pubkyauth://signin_grant?caps=/pub/pubky.app/:rw"
    }
}
