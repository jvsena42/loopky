package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.data.repository.SignerRepository
import com.github.jvsena42.loopky.domain.model.AuthorizedSession
import com.github.jvsena42.loopky.domain.model.RequestedAccess
import com.github.jvsena42.loopky.domain.model.SignInRefusal
import com.github.jvsena42.loopky.domain.model.SignInRefusedException
import com.github.jvsena42.loopky.domain.model.SignInRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSignerRepository : SignerRepository {
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
