package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.repository.AuthFlowHandle
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SignOutOutcome
import com.github.jvsena42.loopky.domain.model.HomeserverLookup
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.KeySource
import com.github.jvsena42.loopky.domain.model.LocalAccount
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * An identity repository that counts the two calls [SessionCache] exists to stop repeating.
 *
 * Everything else throws rather than answering plausibly, which is the rule the deck and card fakes
 * in this module follow: a fake that answers politely turns "this stopped calling the homeserver"
 * into a passing test.
 */
class CountingIdentityRepository(private val session: Session) : IdentityRepository {

    /** `loadPersistedSession` — a `sessionStore.load()`, which on macOS forks `security(1)`. */
    var loads = 0
        private set

    /** `adoptSession` — a `revalidateSession`, and therefore a homeserver round trip. */
    var adoptions = 0
        private set

    override suspend fun loadPersistedSession(): Session? {
        loads++
        return session
    }

    override suspend fun adoptSession(sessionSecret: String): Result<Session> {
        adoptions++
        return Result.success(session)
    }

    private fun no(name: String): Nothing = error("CountingIdentityRepository.$name is not part of this test")

    override suspend fun currentSession(): Session? = no("currentSession")
    override suspend fun signOut(force: Boolean): Result<SignOutOutcome> = no("signOut")
    override suspend fun revokeSession(sessionSecret: String): Result<Unit> = no("revokeSession")
    override val keyCustody: Flow<KeyCustody> = emptyFlow()
    override suspend fun derivePubky(source: KeySource): Result<String> = no("derivePubky")
    override suspend fun lookupHomeserver(pubky: String): HomeserverLookup = no("lookupHomeserver")
    override suspend fun signInWithKey(source: KeySource, knownHomeserver: String?): Result<Session> =
        no("signInWithKey")

    override suspend fun createLocalAccount(homeserverPubky: String, signupToken: String): Result<LocalAccount> =
        no("createLocalAccount")

    override suspend fun registerHeldKey(homeserverPubky: String, signupToken: String): Result<Session> =
        no("registerHeldKey")

    override suspend fun holdKeyForRegistration(source: KeySource): Result<String> = no("holdKeyForRegistration")
    override fun discardUnregisteredKey() = no("discardUnregisteredKey")
    override suspend fun beginSignIn(capabilities: String, returnToApp: Boolean): Result<AuthFlowHandle> =
        no("beginSignIn")

    override suspend fun beginSignUp(
        homeserverPubky: String,
        signupToken: String,
        capabilities: String,
    ): Result<AuthFlowHandle> = no("beginSignUp")

    override suspend fun fetchProfile(pubky: String, forceRefresh: Boolean): Result<PubkyIdentity> =
        no("fetchProfile")

    override suspend fun updateProfile(name: String?, bio: String?): Result<PubkyIdentity> = no("updateProfile")
    override suspend fun deleteAccount(onProgress: (done: Int, total: Int) -> Unit): Result<Unit> =
        no("deleteAccount")
}
