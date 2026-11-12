package com.github.jvsena42.loopky.presentation.settings

import com.github.jvsena42.loopky.data.repository.SignerRepository
import com.github.jvsena42.loopky.domain.model.AuthorizedSession
import com.github.jvsena42.loopky.domain.model.RequestedAccess
import com.github.jvsena42.loopky.domain.model.SignInRefusal
import com.github.jvsena42.loopky.domain.model.SignInRefusedException
import com.github.jvsena42.loopky.domain.model.SignInRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val signer = FakeSigner()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `lists the approved apps`() = runTest(dispatcher) {
        val vm = SessionsViewModel(signer)
        advanceUntilIdle()

        assertEquals(listOf("g1", "me"), vm.state.value.sessions.map { it.grantId })
        assertTrue(vm.state.value.canApprove)
    }

    @Test
    fun `lists nothing when Pubky Ring holds the key`() = runTest(dispatcher) {
        signer.canApproveFlow.value = false
        val vm = SessionsViewModel(signer)
        advanceUntilIdle()

        assertEquals(false, vm.state.value.canApprove)
        assertEquals(0, signer.listCalls)
    }

    @Test
    fun `a link is shown for review and approved only on the tap`() = runTest(dispatcher) {
        val vm = SessionsViewModel(signer)
        advanceUntilIdle()

        vm.onSignInLink(LINK)
        assertEquals(ApprovalPhase.Reviewing, vm.state.value.approval?.phase)
        assertEquals(0, signer.approved.size)

        vm.onApprove()
        advanceUntilIdle()
        assertEquals(ApprovalPhase.Approved, vm.state.value.approval?.phase)
        assertEquals(1, signer.approved.size)

        vm.onDismissApproval()
        advanceUntilIdle()
        assertNull(vm.state.value.approval)
        assertEquals(2, signer.listCalls)
    }

    @Test
    fun `a refused link offers no approval`() = runTest(dispatcher) {
        signer.refusal = SignInRefusal.WholeAccount
        val vm = SessionsViewModel(signer)
        advanceUntilIdle()

        vm.onSignInLink(LINK)
        vm.onApprove()
        advanceUntilIdle()

        assertEquals(ApprovalPhase.Refused, vm.state.value.approval?.phase)
        assertEquals(SignInRefusal.WholeAccount, vm.state.value.approval?.refusal)
        assertEquals(0, signer.approved.size)
    }

    @Test
    fun `a failed approval can be tried again`() = runTest(dispatcher) {
        signer.approveResult = Result.failure(IllegalStateException("relay gone"))
        val vm = SessionsViewModel(signer)
        advanceUntilIdle()

        vm.onSignInLink(LINK)
        vm.onApprove()
        advanceUntilIdle()
        assertEquals(ApprovalPhase.Failed, vm.state.value.approval?.phase)

        signer.approveResult = Result.success(Unit)
        vm.onApprove()
        advanceUntilIdle()
        assertEquals(ApprovalPhase.Approved, vm.state.value.approval?.phase)
    }

    @Test
    fun `revoking removes the row, and this device's own row is never revoked`() = runTest(dispatcher) {
        val vm = SessionsViewModel(signer)
        advanceUntilIdle()

        vm.onRevoke("me")
        vm.onRevoke("g1")
        advanceUntilIdle()

        assertEquals(listOf("g1"), signer.revoked)
        assertEquals(listOf("me"), vm.state.value.sessions.map { it.grantId })
    }

    private class FakeSigner : SignerRepository {
        val canApproveFlow = MutableStateFlow(true)
        override val canApprove: Flow<Boolean> = canApproveFlow
        var refusal: SignInRefusal? = null
        var approveResult: Result<Unit> = Result.success(Unit)
        val approved = mutableListOf<SignInRequest>()
        val revoked = mutableListOf<String>()
        var listCalls = 0

        override fun inspect(text: String): Result<SignInRequest> = refusal
            ?.let { Result.failure(SignInRefusedException(it)) }
            ?: Result.success(SignInRequest(text, "mcp.loopky.app", listOf(RequestedAccess("/pub/loopky/", true))))

        override suspend fun approve(request: SignInRequest): Result<Unit> =
            approveResult.onSuccess { approved += request }

        override suspend fun authorizedSessions(): Result<List<AuthorizedSession>> {
            listCalls++
            return Result.success(
                listOf(
                    AuthorizedSession("g1", "mcp.loopky.app", emptyList(), 200, 900, isThisDevice = false),
                    AuthorizedSession("me", "loopky.app", emptyList(), 100, 900, isThisDevice = true),
                ),
            )
        }

        override suspend fun revoke(grantId: String): Result<Unit> {
            revoked += grantId
            return Result.success(Unit)
        }
    }

    private companion object {
        const val LINK = "pubkyauth://signin_grant?caps=/pub/loopky/:rw"
    }
}
